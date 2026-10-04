import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from knowledge import build_knowledge, extract


class KnowledgeTests(unittest.TestCase):
    def test_noise_removed_and_hidden_information_retained(self):
        html = '''<nav>MENU NOISE</nav><div class="mobile-menu">MORE NOISE</div>
        <script>TRACKING CODE</script><style>CSS NOISE</style>
        <article class="service-detail" id="finish" hidden>
        <span class="svc-kicker">Wood Coatings</span><h3>Timber finishes</h3>
        <p>At least <strong>60 days</strong> on site.</p>
        <button>Close</button></article><form>FORM NOISE</form>'''
        blocks, _, _ = extract(html, 'https://example.com/')
        text = ' '.join(b['text'] for b in blocks)
        self.assertIn('At least 60 days on site.', text)
        for noise in ('NOISE', 'TRACKING', 'Close'):
            self.assertNotIn(noise, text)
        paragraph = next(b for b in blocks if '60 days' in b['text'])
        self.assertIn('Wood Coatings', paragraph['heading_path'])
        self.assertEqual(paragraph['source_url'], 'https://example.com/#finish')

    def test_faq_pair_and_contacts(self):
        html = '''<div class="faq-item"><div class="faq-q-text">Where?</div>
        <div class="faq-a">Mumbai.</div><span class="faq-toggle">+</span></div>
        <footer><a href="#about">Explore</a>
        <a href="mailto:hello@example.com">Email us</a></footer>'''
        blocks, _, _ = extract(html, 'https://example.com/')
        self.assertEqual(blocks[0]['type'], 'faq')
        self.assertIn('Answer: Mumbai.', blocks[0]['text'])
        self.assertIn('mailto:hello@example.com', blocks[1]['text'])
        self.assertNotIn('Explore', str(blocks))

    def test_schema_facts_preserved_without_tracking(self):
        html = '''<script type="application/ld+json">{"@type":"Organization",
        "name":"Brand","contactPoint":{"telephone":"123"}}</script>'''
        blocks, schemas, warnings = extract(html, 'https://example.com/')
        self.assertEqual(len(schemas), 1)
        self.assertIn('contactPoint.telephone: 123', blocks[0]['text'])
        self.assertFalse(warnings)

    def test_offline_archive_immutable_and_chunk_limit(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as tmp:
            root = Path(tmp)
            raw = root / 'raw'
            raw.mkdir()
            (raw / 'raw.html').write_text('<h1>Title</h1><p>' + 'word ' * 25 + '</p>', encoding='utf-8')
            (raw / 'meta.json').write_text('{}', encoding='utf-8')
            (raw / 'manifest.json').write_text(json.dumps({'pages': [{
                'html': 'raw.html', 'metadata': 'meta.json', 'url': 'https://example.com/',
                'title': 'Title', 'fetched_at': '2026-10-03'}]}), encoding='utf-8')
            before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in raw.iterdir()}
            build_knowledge(raw, root / 'knowledge', 10)
            after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in raw.iterdir()}
            self.assertEqual(before, after)
            chunks = [json.loads(line) for line in (root / 'knowledge/chunks.jsonl').read_text().splitlines()]
            self.assertEqual(len(chunks), 3)
            self.assertTrue(all(len(c['text'].split('\n\n')[1].split()) <= 10 for c in chunks))
            self.assertTrue(all(c['block_indices'] == [1] for c in chunks))
            with self.assertRaises(ValueError):
                build_knowledge(raw, raw / 'knowledge')


if __name__ == '__main__':
    unittest.main()

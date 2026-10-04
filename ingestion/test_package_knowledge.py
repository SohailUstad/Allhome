import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

SCRIPT = Path(__file__).parent / 'package_knowledge.py'


def chunk(chunk_id, text='Lime wash suits living rooms.'):
    return {'id': chunk_id, 'document_id': 'doc1', 'text': text, 'url': 'https://example.com/'}


class PackageKnowledgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.chunks = self.dir / 'chunks.jsonl'
        self.output = self.dir / 'knowledge.zip'

    def tearDown(self):
        self.tmp.cleanup()

    def package(self, chunks, *extra):
        self.chunks.write_text(''.join(json.dumps(c) + '\n' for c in chunks), encoding='utf-8')
        return subprocess.run([sys.executable, str(SCRIPT), '--chunks', str(self.chunks),
                               '--output', str(self.output), *extra],
                              capture_output=True, text=True)

    def test_valid_chunks_packaged_with_manifest(self):
        result = self.package([chunk('a'), chunk('b')], '--dataset-version', '2026-10-04')
        self.assertEqual(result.returncode, 0, result.stderr)
        with ZipFile(self.output) as archive:
            self.assertEqual(sorted(archive.namelist()), ['chunks.jsonl', 'manifest.json'])
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(manifest['dataset_id'], 'colourcoats')
            self.assertEqual(manifest['dataset_version'], '2026-10-04')
            self.assertEqual(manifest['chunk_count'], 2)
            # Chunks are stored byte for byte as extracted.
            self.assertEqual(archive.read('chunks.jsonl'), self.chunks.read_bytes())

    def test_invalid_chunks_rejected_without_output(self):
        cases = {
            'Duplicate chunk id': [chunk('a'), chunk('a')],
            'Missing/non-text chunk field: url': [{k: v for k, v in chunk('a').items() if k != 'url'}],
            'Missing/non-text chunk field: text': [chunk('a', text='   ')],
            'exceeds server limit': [chunk('a', text='x' * 8001)],
            'No chunks to package': [],
        }
        for message, chunks in cases.items():
            with self.subTest(message):
                result = self.package(chunks)
                self.assertEqual(result.returncode, 2)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()

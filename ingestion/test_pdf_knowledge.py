from pathlib import Path
import tempfile
import unittest

from pdf_knowledge import build_chunks, page_texts

URL = 'https://example.com/brochure.pdf'


def tiny_pdf(texts):
    """A minimal valid PDF with one page per entry; an empty string makes a page without a text layer."""
    objects = ['<< /Type /Catalog /Pages 2 0 R >>',
               '<< /Type /Pages /Kids [%s] /Count %d >>' % (' '.join(f'{4 + 2 * i} 0 R' for i in range(len(texts))),
                                                            len(texts)),
               '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    for i, text in enumerate(texts):
        content = f'BT /F1 12 Tf 72 720 Td ({text}) Tj ET' if text else ''
        objects.append('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] '
                       f'/Resources << /Font << /F1 3 0 R >> >> /Contents {5 + 2 * i} 0 R >>')
        objects.append(f'<< /Length {len(content)} >>\nstream\n{content}\nendstream')
    pdf = b'%PDF-1.4\n'
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf += f'{number} 0 obj\n{body}\nendobj\n'.encode('latin-1')
    xref = len(pdf)
    pdf += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    pdf += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets)
    pdf += f'trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    return pdf


class PdfKnowledgeTests(unittest.TestCase):
    def test_reads_text_layer_and_skips_image_only_pages(self):
        with tempfile.TemporaryDirectory() as tmp:
            pdf = Path(tmp) / 'brochure.pdf'
            pdf.write_bytes(tiny_pdf(['Wall Textures', '', 'Calceterra lime plaster']))
            self.assertEqual(page_texts(pdf), [(1, 'Wall Textures'), (3, 'Calceterra lime plaster')])

    def test_short_pages_are_packed_with_their_neighbours(self):
        pages = [(5, 'Wall Textures'), (6, 'Shabby Chic'), (7, 'Persia'), (9, 'Calceterra')]
        chunks = build_chunks(pages, URL, 'Brochure', '2026-10-04T00:00:00+00:00', chunk_words=300)

        self.assertEqual(len(chunks), 1)
        chunk = chunks[0]
        self.assertEqual(chunk['text'], 'Brochure\nPages 5-9\n\nWall Textures Shabby Chic Persia Calceterra')
        self.assertEqual(chunk['heading_path'], ['Pages 5-9'])
        self.assertEqual(chunk['source_urls'], [f'{URL}#page={n}' for n in (5, 6, 7, 9)])
        self.assertEqual(chunk['url'], URL)
        self.assertEqual(chunk['title'], 'Brochure')
        self.assertEqual(chunk['fetched_at'], '2026-10-04T00:00:00+00:00')

    def test_chunks_respect_the_word_limit_and_split_long_pages(self):
        long_page = ' '.join(f'w{i}' for i in range(25))
        pages = [(1, 'one two three'), (2, long_page), (3, 'last page')]
        chunks = build_chunks(pages, URL, 'Brochure', None, chunk_words=10)

        bodies = [c['text'].split('\n\n', 1)[1].split() for c in chunks]
        self.assertTrue(all(len(body) <= 10 for body in bodies))
        self.assertEqual(sum(len(body) for body in bodies), 3 + 25 + 2)  # nothing lost
        # Page 2 is cut into 10 + 10 + 5 words; page 1 (3) + 10 would exceed the limit, the last 5 + page 3 fit.
        self.assertEqual([c['heading_path'] for c in chunks],
                         [['Page 1'], ['Page 2'], ['Page 2'], ['Pages 2-3']])

    def test_ids_are_unique_and_stable(self):
        pages = [(1, 'alpha ' * 8), (2, 'beta ' * 8)]
        first = build_chunks(pages, URL, 'Brochure', None, chunk_words=10)
        again = build_chunks(pages, URL, 'Brochure', None, chunk_words=10)
        self.assertEqual([c['id'] for c in first], [c['id'] for c in again])
        self.assertEqual(len({c['id'] for c in first}), len(first))
        self.assertEqual(len({c['document_id'] for c in first}), 1)


if __name__ == '__main__':
    unittest.main()

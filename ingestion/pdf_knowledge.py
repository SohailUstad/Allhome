"""Extract retrieval chunks from a PDF's text layer (no OCR).

Writes chunks in the same format as knowledge.py, so package_knowledge.py can bundle them with the website chunks.
Pages without a text layer (image-only pages) are skipped. Consecutive pages are packed into chunks of at most
--chunk-words words, so pages that hold only a product name are kept together with their neighbours; longer pages
are split. Every chunk links to the exact PDF pages it came from (url#page=N).
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from pypdf import PdfReader


def page_texts(pdf_path):
    """[(page_number, text)] for pages with a text layer, whitespace normalised."""
    pages = []
    for number, page in enumerate(PdfReader(pdf_path).pages, start=1):
        text = ' '.join((page.extract_text() or '').split())
        if text:
            pages.append((number, text))
    return pages


def page_label(numbers):
    return f'Page {numbers[0]}' if len(numbers) == 1 else f'Pages {numbers[0]}-{numbers[-1]}'


def build_chunks(pages, url, title, fetched_at, chunk_words=300):
    """Pack consecutive pages into chunks of at most chunk_words words (body only, header excluded)."""
    document_id = hashlib.sha256(url.encode()).hexdigest()[:16]
    pieces = []  # (page_number, words) with no piece longer than chunk_words
    for number, text in pages:
        words = text.split()
        for offset in range(0, len(words), chunk_words):
            pieces.append((number, words[offset:offset + chunk_words]))

    groups = []
    for number, words in pieces:
        if groups and len(groups[-1]['words']) + len(words) <= chunk_words:
            groups[-1]['words'] += words
            if number != groups[-1]['pages'][-1]:
                groups[-1]['pages'].append(number)
        else:
            groups.append({'words': list(words), 'pages': [number]})

    chunks = []
    for group in groups:
        label = page_label(group['pages'])
        text = title + '\n' + label + '\n\n' + ' '.join(group['words'])
        chunks.append({
            'id': hashlib.sha256((url + text).encode()).hexdigest(),
            'document_id': document_id,
            'text': text,
            'url': url,
            'title': title,
            'fetched_at': fetched_at,
            'heading_path': [label],
            'source_urls': [f'{url}#page={n}' for n in group['pages']],
        })
    return chunks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pdf', help='PDF file to extract')
    parser.add_argument('--url', required=True, help='Public URL of the PDF, used for citations')
    parser.add_argument('--title', required=True, help='Title shown at the top of every chunk')
    parser.add_argument('--output', default='knowledge/pdf_chunks.jsonl')
    parser.add_argument('--chunk-words', type=int, default=300,
                        help='Maximum words in each chunk body (default: 300, same as the website chunks)')
    args = parser.parse_args()
    if args.chunk_words < 1:
        parser.error('chunk-words must be positive')

    pages = page_texts(args.pdf)
    if not pages:
        parser.error('The PDF has no text layer; it would need OCR')
    fetched_at = datetime.fromtimestamp(Path(args.pdf).stat().st_mtime, timezone.utc).isoformat()
    chunks = build_chunks(pages, args.url, args.title, fetched_at, args.chunk_words)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('w', encoding='utf-8', newline='\n') as handle:
        for chunk in chunks:
            handle.write(json.dumps(chunk, ensure_ascii=False) + '\n')
    print(f'Extracted {len(pages)} pages with text into {len(chunks)} chunks in {output}')


if __name__ == '__main__':
    main()

"""Package extracted chunks for the Spring AI ingestion endpoint."""
import argparse
from datetime import datetime
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chunks', default='knowledge/chunks.jsonl')
    parser.add_argument('--output', default='colourcoats-knowledge.zip')
    parser.add_argument('--dataset-id', default='colourcoats')
    parser.add_argument('--dataset-version', default=datetime.now().astimezone().date().isoformat())
    parser.add_argument('--source', default='https://www.colourcoats.com/')
    args = parser.parse_args()
    data = Path(args.chunks).read_bytes()
    chunks = [json.loads(line) for line in data.decode('utf-8-sig').splitlines() if line.strip()]
    if not chunks:
        parser.error('No chunks to package')
    ids = set()
    for chunk in chunks:
        for key in ('id', 'document_id', 'text', 'url'):
            if not isinstance(chunk.get(key), str) or not chunk[key].strip():
                parser.error(f'Missing/non-text chunk field: {key}')
        if chunk['id'] in ids:
            parser.error(f'Duplicate chunk id: {chunk["id"]}')
        ids.add(chunk['id'])
        if len(chunk['text'].encode('utf-8')) > 8000:
            parser.error('Chunk exceeds server limit; rebuild with a smaller --chunk-words')
    manifest = {'schema_version': '1.0', 'dataset_id': args.dataset_id,
                'dataset_version': args.dataset_version, 'source': args.source,
                'language': 'en', 'chunks_file': 'chunks.jsonl', 'chunk_count': len(chunks)}
    with ZipFile(args.output, 'w', ZIP_DEFLATED) as archive:
        archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
        archive.writestr('chunks.jsonl', data)
    print(f'Packaged {len(chunks)} chunks into {args.output}')


if __name__ == '__main__':
    main()

"""Offline, source-preserving HTML extraction for retrieval preparation."""
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import urljoin


class Node:
    def __init__(self, tag='', attrs=()):
        self.tag, self.attrs, self.children = tag, dict(attrs), []


class Tree(HTMLParser):
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
            'link', 'meta', 'param', 'source', 'track', 'wbr'}

    def __init__(self):
        super().__init__()
        self.root = Node()
        self.stack = [self.root]

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag not in self.VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def classes(node):
    return set(node.attrs.get('class', '').split())


def clean(text):
    return ' '.join(text.split())


def excluded(node):
    return (node.tag in {'head', 'script', 'style', 'noscript', 'template', 'nav',
                        'svg', 'iframe', 'form', 'input', 'select', 'textarea'}
            or node.attrs.get('role') in {'navigation', 'dialog'}
            or bool(classes(node) & {'breaker', 'faq-num', 'faq-toggle', 'modal-overlay',
                                    'mobile-menu', 'hero-actions', 'svc-ctas', 'svc-more',
                                    'hero-scroll', 'hero-cta', 'form-success', 'ico'})
            or node.attrs.get('id') in {'pf-success'}
            or (node.tag == 'button' and not classes(node) & {'service-card'}))


def content(node):
    if isinstance(node, str):
        return node
    if excluded(node):
        return ''
    if node.tag == 'br':
        return ' '
    if node.tag == 'img':
        # Inline brand images substitute for words; decorative photos do not.
        return node.attrs.get('alt', '') if 'logo' in node.attrs.get('src', '') else ''
    return ' '.join(content(child) for child in node.children)


def descendants(node):
    yield node
    for child in node.children:
        if isinstance(child, Node):
            yield from descendants(child)


def extract(html, url):
    tree = Tree()
    tree.feed(html)
    blocks, schemas, warnings = [], [], []
    seen = set()

    def add(kind, text, context, anchor='', links=None):
        text = clean(text)
        key = (kind, tuple(context), text)
        if not text or key in seen or text in {'Scroll', 'Read the story →'}:
            return
        seen.add(key)
        blocks.append({'type': kind, 'text': text, 'heading_path': list(context),
                       'source_url': url + ('#' + anchor if anchor else ''),
                       'links': links or []})

    def walk(node, context, anchor=''):
        if excluded(node):
            return
        anchor = node.attrs.get('id') or anchor
        if node.tag == 'footer':
            for child in descendants(node):
                href = child.attrs.get('href', '')
                if href.startswith(('tel:', 'mailto:', 'https://wa.me/')):
                    add('contact', content(child) + ' ' + href, ['Contact'], anchor)
            return
        if 'faq-item' in classes(node):
            q = next((n for n in descendants(node) if 'faq-q-text' in classes(n)), None)
            a = next((n for n in descendants(node) if 'faq-a' in classes(n)), None)
            if q and a:
                add('faq', 'Question: ' + content(q) + '\nAnswer: ' + content(a), context, anchor)
                return
        if node.tag in {'h1', 'h2', 'h3', 'h4', 'h5', 'h6'}:
            level = int(node.tag[1])
            context[:] = context[:level - 1] + [clean(content(node))]
            add('heading', content(node), context, anchor)
            return
        if node.tag in {'section', 'article'}:
            context = list(context)
            label = next((clean(content(n)) for n in descendants(node)
                          if classes(n) & {'svc-kicker', 'section-label'}), '')
            if label:
                context = [label]
        if classes(node) & {'stat', 'about-badge'}:
            add('fact', content(node), context, anchor)
            return
        # Preserve leaf paragraphs and composite cards/statistics as coherent blocks.
        nested_blocks = any(c is not node and c.tag in
                            {'div', 'section', 'article', 'p', 'ul', 'ol', 'li',
                             'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'tr'}
                            for c in descendants(node))
        if not nested_blocks and node.tag not in {'', 'html', 'body', 'main'}:
            links = [{'text': clean(content(n)), 'url': urljoin(url, n.attrs['href'])}
                     for n in descendants(node) if n.tag == 'a' and n.attrs.get('href')]
            # Standalone calls to action are interface text; retain download references.
            if node.tag == 'a' and not node.attrs.get('href', '').endswith('.pdf'):
                return
            add('list_item' if node.tag == 'li' else 'paragraph', content(node), context, anchor, links)
            return
        pending = []
        for child in node.children:
            if isinstance(child, str):
                pending.append(child)
            else:
                if clean(''.join(pending)):
                    add('paragraph', ''.join(pending), context, anchor)
                pending = []
                walk(child, context, anchor)
        if pending:
            add('paragraph', ''.join(pending), context, anchor)

    walk(tree.root, [])
    for node in descendants(tree.root):
        if node.tag == 'script' and node.attrs.get('type') == 'application/ld+json':
            try:
                schemas.append(json.loads(''.join(c for c in node.children if isinstance(c, str))))
            except json.JSONDecodeError as exc:
                warnings.append(f'Invalid JSON-LD: {exc}')

    def facts(value, prefix=''):
        if isinstance(value, dict):
            return [line for key, val in value.items() if not key.startswith('@')
                    and key not in {'logo', 'image', 'potentialAction'}
                    for line in facts(val, f'{prefix}.{key}' if prefix else key)]
        if isinstance(value, list):
            return [line for i, val in enumerate(value) for line in facts(val, f'{prefix}[{i}]')]
        return [f'{prefix}: {value}']

    for schema in schemas:
        entities = schema if isinstance(schema, list) else schema.get('@graph', [schema])
        for entity in entities:
            if isinstance(entity, dict):
                label = entity.get('name') or entity.get('@type', 'Structured data')
                add('structured_data', '\n'.join(facts(entity)), [str(label)])
    return blocks, schemas, warnings


def build_knowledge(raw_dir, output_dir, chunk_words=300):
    raw_dir, output_dir = Path(raw_dir).resolve(), Path(output_dir).resolve()
    if output_dir == raw_dir or raw_dir in output_dir.parents or output_dir in raw_dir.parents:
        raise ValueError('Knowledge directory must be separate from the raw archive directory')
    manifest = json.loads((raw_dir / 'manifest.json').read_text(encoding='utf-8'))
    output_dir.mkdir(parents=True, exist_ok=True)
    documents, chunks = [], []
    for entry in manifest['pages']:
        source = (raw_dir / entry['html']).resolve()
        meta_path = (raw_dir / entry['metadata']).resolve()
        if raw_dir not in source.parents or raw_dir not in meta_path.parents:
            raise ValueError('Manifest paths must stay inside the archive directory')
        metadata = json.loads(meta_path.read_text(encoding='utf-8'))
        data = source.read_bytes()
        encoding = metadata.get('declared_encoding') or 'utf-8'
        blocks, schemas, warnings = extract(data.decode(encoding, errors='replace'), entry['url'])
        page_id = hashlib.sha256(entry['url'].encode()).hexdigest()[:16]
        doc = {'id': page_id, 'url': entry['url'], 'title': entry['title'],
               'fetched_at': entry['fetched_at'], 'raw_sha256': hashlib.sha256(data).hexdigest(),
               'blocks': blocks, 'structured_data': schemas, 'warnings': warnings}
        documents.append(doc)
        (output_dir / f'{page_id}.json').write_text(
            json.dumps(doc, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        (output_dir / f'{page_id}.md').write_text(
            '# ' + doc['title'] + '\n\n' + '\n\n'.join(
                ('## ' if b['type'] == 'heading' else '') + b['text'] for b in blocks) + '\n',
            encoding='utf-8')
        # Pack adjacent blocks under the same heading; never mix unrelated sections.
        groups = []
        for index, block in enumerate(blocks):
            if block['type'] == 'heading':
                continue
            words = block['text'].split()
            for offset in range(0, len(words), chunk_words):
                piece = ' '.join(words[offset:offset + chunk_words])
                context = block['heading_path']
                if (groups and groups[-1]['heading_path'] == context
                        and len((groups[-1]['body'] + ' ' + piece).split()) <= chunk_words):
                    groups[-1]['body'] += '\n' + piece
                    groups[-1]['block_indices'].append(index)
                    if block['source_url'] not in groups[-1]['source_urls']:
                        groups[-1]['source_urls'].append(block['source_url'])
                else:
                    groups.append({'body': piece, 'heading_path': context,
                                   'block_indices': [index], 'source_urls': [block['source_url']]})
        for group in groups:
            text = doc['title'] + '\n' + ' > '.join(group['heading_path']) + '\n\n' + group.pop('body')
            chunk_id = hashlib.sha256((entry['url'] + text).encode()).hexdigest()
            chunks.append({'id': chunk_id, 'document_id': page_id, 'text': text,
                           'url': entry['url'], 'title': doc['title'],
                           'fetched_at': doc['fetched_at'], **group})
    with (output_dir / 'chunks.jsonl').open('w', encoding='utf-8') as handle:
        for chunk in chunks:
            handle.write(json.dumps(chunk, ensure_ascii=False) + '\n')
    summary = {'document_count': len(documents), 'chunk_count': len(chunks),
               'chunk_body_max_words': chunk_words,
               'notes': ['Source wording retained; no facts inferred or reconciled.',
                         'Hidden service descriptions and FAQ answers are retained.',
                         'JavaScript-only content and linked PDF contents are not available.',
                         'Heading context is included in every chunk; word limits exclude this prefix.'],
               'documents': [{'id': d['id'], 'url': d['url'], 'block_count': len(d['blocks']),
                              'warnings': d['warnings']} for d in documents]}
    (output_dir / 'manifest.json').write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Extracted {len(documents)} documents and {len(chunks)} RAG chunks into {output_dir}')
    return 0 if documents else 1

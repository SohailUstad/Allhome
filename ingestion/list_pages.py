"""Discover public HTML pages and archive their raw information.

Requires only Python's standard library. Does not execute JavaScript.
"""

import argparse
from collections import deque
import csv
import gzip
import hashlib
import json
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.request import Request
from urllib.robotparser import RobotFileParser
import xml.etree.ElementTree as ET


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.base = None
        self.title = []
        self.in_title = False
        self.text = []
        self.excluded = []
        self.elements = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.elements.append({'tag': tag, 'attributes': attrs})
        if tag in ('script', 'style', 'template'):
            self.excluded.append(tag)
        if tag in ('p', 'div', 'section', 'article', 'li', 'br', 'hr',
                   'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'tr'):
            self.text.append('\n')
        if tag == 'a' and attrs.get('href'):
            self.links.append(attrs['href'])
        if tag == 'base' and attrs.get('href') and self.base is None:
            self.base = attrs['href']
        if tag == 'title':
            self.in_title = True

    def handle_endtag(self, tag):
        if self.excluded and tag == self.excluded[-1]:
            self.excluded.pop()
        if tag in ('p', 'div', 'section', 'article', 'li', 'h1', 'h2',
                   'h3', 'h4', 'h5', 'h6', 'tr'):
            self.text.append('\n')
        if tag == 'title':
            self.in_title = False

    def handle_data(self, data):
        if not self.excluded:
            self.text.append(data)
        if self.in_title:
            self.title.append(data)


def save_page(directory, url, requested_url, headers, data, page, fetched_at):
    """Keep original response bytes; derived text is only a convenience view."""
    page_id = hashlib.sha256(url.encode('utf-8')).hexdigest()[:16]
    folder = directory / page_id
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'raw.html').write_bytes(data)
    lines = [' '.join(line.split()) for line in ''.join(page.text).splitlines()]
    text = '\n'.join(line for line in lines if line)
    (folder / 'text.txt').write_text(text + '\n', encoding='utf-8')
    base = urljoin(url, page.base) if page.base else url
    metadata = {
        'url': url,
        'requested_url': requested_url,
        'fetched_at': fetched_at,
        'title': ' '.join(''.join(page.title).split()),
        'content_type': headers.get_content_type(),
        'declared_encoding': headers.get_content_charset(),
        'response_headers': list(headers.items()),
        'html_bytes': len(data),
        'sha256': hashlib.sha256(data).hexdigest(),
        'links': [{'href': href, 'resolved_url': urljoin(base, href)}
                  for href in page.links],
        'elements': page.elements,
        'notes': 'HTML as received after HTTP decompression. JavaScript was not '
                 'executed. Text can include hidden content. Linked assets were not downloaded.',
    }
    (folder / 'metadata.json').write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return {'url': url, 'title': metadata['title'], 'fetched_at': fetched_at,
            'html': f'{page_id}/raw.html', 'text': f'{page_id}/text.txt',
            'metadata': f'{page_id}/metadata.json'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url', nargs='?', default='https://www.colourcoats.com/')
    parser.add_argument('--output', default='pages.csv')
    parser.add_argument('--stage', choices=['list', 'all', 'knowledge'], default='all',
                        help='list: inventory; all: inventory + raw; knowledge: offline RAG extraction')
    parser.add_argument('--knowledge-dir', default='knowledge')
    parser.add_argument('--chunk-words', type=int, default=300,
                        help='Maximum words in each chunk body (default: 300)')
    parser.add_argument('--raw-dir', default='raw_pages',
                        help='Directory for archived pages and manifest.json')
    parser.add_argument('--max-pages', type=int, default=1000,
                        help='Maximum candidate page requests (default: 1000)')
    parser.add_argument('--delay', type=float, default=0.5)
    parser.add_argument('--timeout', type=float, default=20)
    parser.add_argument('--include-query', action='store_true',
                        help='Include query URLs; may discover many duplicates')
    args = parser.parse_args()
    if args.chunk_words < 1:
        parser.error('chunk-words must be positive')
    if args.stage == 'knowledge':
        from knowledge import build_knowledge
        return build_knowledge(args.raw_dir, args.knowledge_dir, args.chunk_words)
    if args.max_pages < 1 or args.delay < 0 or args.timeout <= 0:
        parser.error('max-pages and timeout must be positive; delay cannot be negative')
    start = urlsplit(args.url)
    if start.scheme not in ('http', 'https') or not start.hostname:
        parser.error('Provide an absolute HTTP or HTTPS URL')
    host = start.hostname.lower().removeprefix('www.')
    agent = 'PageInventory/1.0'
    robots = {}
    last_request = 0.0
    rows = {}
    errors = []
    archives = {}
    raw_dir = Path(args.raw_dir)
    if args.stage == 'all':
        raw_dir.mkdir(parents=True, exist_ok=True)

    def same_site(url):
        parts = urlsplit(url)
        return (parts.scheme in ('http', 'https')
                and (parts.hostname or '').lower().removeprefix('www.') == host
                and not parts.username and not parts.password
                and parts.port == start.port)

    def normalize(url):
        parts = urlsplit(url)
        return urlunsplit((parts.scheme.lower(), parts.netloc.lower(),
                           parts.path or '/', parts.query if args.include_query else '', ''))

    def fetch(url, delay=None):
        nonlocal last_request
        pause = max(args.delay, delay or 0) - (time.monotonic() - last_request)
        if pause > 0:
            time.sleep(pause)
        last_request = time.monotonic()
        # Check each redirect before following it, so crawling stays on this site.
        from urllib.request import HTTPRedirectHandler, build_opener

        class Redirects(HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                if not same_site(newurl) or not allowed(newurl):
                    raise URLError('Redirect outside site or blocked by robots.txt')
                return super().redirect_request(req, fp, code, msg, headers, newurl)

        with build_opener(Redirects()).open(
                Request(url, headers={'User-Agent': agent}), timeout=args.timeout) as response:
            data = response.read(10_000_001)
            if len(data) > 10_000_000:
                raise ValueError('Response exceeds 10 MB limit')
            if response.headers.get('Content-Encoding') == 'gzip' or data[:2] == b'\x1f\x8b':
                data = gzip.decompress(data)
            return response.url, response.headers, data

    def rules(url):
        parts = urlsplit(url)
        origin = urlunsplit((parts.scheme, parts.netloc, '', '', ''))
        if origin not in robots:
            rule = RobotFileParser()
            robots[origin] = rule  # Prevent recursion on robots redirects.
            try:
                _, _, data = fetch(origin + '/robots.txt')
                rule.parse(data.decode('utf-8', errors='replace').splitlines())
            except HTTPError as exc:
                rule.parse(['User-agent: *', 'Disallow: /' if exc.code not in (404, 410) else 'Disallow:'])
                errors.append(f'robots.txt {origin}: HTTP {exc.code}')
            except (URLError, OSError, ValueError) as exc:
                rule.parse(['User-agent: *', 'Disallow: /'])
                errors.append(f'robots.txt {origin}: {exc}; crawl skipped for this origin')
        return robots[origin]

    def allowed(url):
        return rules(url).can_fetch(agent, url)

    origin = urlunsplit((start.scheme, start.netloc, '', '', ''))
    rule = rules(args.url)
    sitemap_queue = deque(rule.site_maps() or [])
    sitemap_queue.extend([origin + '/sitemap.xml', origin + '/sitemap_index.xml'])
    seen_maps = set()
    queue = deque([normalize(args.url)])
    while sitemap_queue and len(seen_maps) < 100:
        url = sitemap_queue.popleft()
        if url in seen_maps or not same_site(url):
            continue
        seen_maps.add(url)
        if not allowed(url):
            continue
        try:
            _, _, data = fetch(url, rules(url).crawl_delay(agent))
            root = ET.fromstring(data)
            kind = root.tag.rsplit('}', 1)[-1]
            if kind not in ('sitemapindex', 'urlset'):
                continue
            for entry in root:
                loc = next((node.text for node in entry
                            if node.tag.rsplit('}', 1)[-1] == 'loc'), None)
                if loc:
                    target = urljoin(url, loc.strip())
                    if kind == 'sitemapindex':
                        sitemap_queue.append(target)
                    elif same_site(target):
                        queue.append(normalize(target))
        except (URLError, OSError, ValueError, ET.ParseError) as exc:
            errors.append(f'Sitemap {url}: {exc}')

    seen = set()
    skipped = 0
    requests = 0
    assets = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.svg', '.ico', '.pdf',
              '.css', '.js', '.zip', '.mp4', '.mp3', '.woff', '.woff2', '.xml'}
    while queue and requests < args.max_pages:
        url = queue.popleft()
        if url in seen:
            continue
        seen.add(url)
        if Path(urlsplit(url).path).suffix.lower() in assets:
            continue
        if not allowed(url):
            skipped += 1
            continue
        requests += 1
        try:
            final, headers, data = fetch(url, rules(url).crawl_delay(agent))
            if headers.get_content_type() not in ('text/html', 'application/xhtml+xml'):
                continue
            final = normalize(final)
            page = Links()
            page.feed(data.decode(headers.get_content_charset() or 'utf-8', errors='replace'))
            rows[final] = {'url': final, 'title': ' '.join(''.join(page.title).split())}
            if args.stage == 'all':
                archives[final] = save_page(
                    raw_dir, final, url, headers, data, page,
                    datetime.now(timezone.utc).isoformat())
            print(final)
            seen.add(final)
            base = urljoin(final, page.base) if page.base else final
            for link in page.links:
                target = urljoin(base, link)
                if same_site(target):
                    queue.append(normalize(target))
        except (URLError, OSError, ValueError) as exc:
            errors.append(f'Page {url}: {exc}')

    with open(args.output, 'w', newline='', encoding='utf-8-sig') as output:
        writer = csv.DictWriter(output, fieldnames=['url', 'title'])
        writer.writeheader()
        writer.writerows(rows[url] for url in sorted(rows))
    print(f'\nSaved {len(rows)} public HTML pages to {args.output}. '
          f'{skipped} URLs blocked by robots.txt.', file=sys.stderr)
    if queue or sitemap_queue:
        print('Discovery limit reached; inventory may be incomplete.', file=sys.stderr)
    if args.stage == 'all':
        manifest = {
            'site': args.url,
            'completed_at': datetime.now(timezone.utc).isoformat(),
            'page_count': len(archives),
            'discovery_limit_reached': bool(queue or sitemap_queue),
            'robots_blocked_count': skipped,
            'errors': errors,
            'pages': [archives[url] for url in sorted(archives)],
        }
        (raw_dir / 'manifest.json').write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'Archived {len(archives)} pages in {raw_dir}.', file=sys.stderr)
    for error in errors:
        print(error, file=sys.stderr)
    return 0 if rows else 1


if __name__ == '__main__':
    sys.exit(main())

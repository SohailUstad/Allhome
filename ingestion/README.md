# Ingestion

Turns the ColourCoats website and brochure PDF into text chunks for the chat assistant's knowledge base.
Python 3.9+. The website scripts use only the standard library; the PDF script needs `pypdf`:

```
python -m pip install -r requirements.txt
```

## Steps

1. Crawl the site and archive raw pages (respects `robots.txt`, stays on the site, does not run JavaScript):

   ```
   python list_pages.py                      # https://www.colourcoats.com/ by default
   ```

   Writes `pages.csv` (page inventory) and `raw_pages/` (raw HTML, text and metadata per page, plus `manifest.json`).
   Use `--stage list` for the inventory only. Options: `--max-pages`, `--delay`, `--timeout`, `--include-query`.

2. Extract knowledge from the archived pages (offline):

   ```
   python list_pages.py --stage knowledge
   ```

   Writes `knowledge/chunks.jsonl` (one retrieval chunk per line), a `.json`/`.md` file per page and `manifest.json`.
   `--chunk-words` sets the maximum words per chunk body (default 300).

3. Extract knowledge from a PDF's text layer (offline):

   ```
   python pdf_knowledge.py colourcoats-brochure-2026.pdf --url https://www.colourcoats.com/brochure-2026.pdf --title "ColourCoats Brochure 2026"
   ```

   Writes `knowledge/pdf_chunks.jsonl` in the same format as step 2. Pages without a text layer are skipped.
   Consecutive pages are packed into chunks of at most `--chunk-words` words (default 300), so pages that hold
   only a product name stay together with their neighbours; longer pages are split. Each chunk starts with the
   title and its pages ("Pages 3-9") and links to them (`...brochure-2026.pdf#page=3`) for citations.

   The PDFs are downloadable from the site (`/brochure-2026.pdf`, `/textures-2026.pdf`, `/wood-coatings-2026.pdf`).
   Only the brochure has a usable text layer (about 1,050 words on 22 pages, giving 4 chunks): the textures and
   wood-coatings catalogues are image-only pages and would need OCR or a vision model, which is not done.
   With 300 words, the brochure's last chunk combines product pages with the contact page; a smaller
   `--chunk-words` (e.g. 150) gives more focused chunks if retrieval needs it.

4. Package the chunks for the Spring app's ingestion endpoint:

   ```
   python package_knowledge.py                                                   # website only
   python package_knowledge.py --chunks knowledge/chunks.jsonl knowledge/pdf_chunks.jsonl --dataset-version 2026-10-04-pdf
   ```

   Accepts one or more chunk files and combines them into one zip with `manifest.json` and `chunks.jsonl`.
   Validates every chunk (`id`, `document_id`, `text`, `url` present; unique ids across all files; at most 8000
   bytes of text). Options: `--chunks`, `--output`, `--dataset-id`, `--dataset-version` (default: today),
   `--source`. Use a new `--dataset-version` whenever the content changes: the service rejects the same version
   with different content.

Generated files are git-ignored; rerun the scripts to rebuild them. Upload the zip as described in the
repository README ("Load knowledge"); the new version becomes the active one.

## Tests

```
python -m unittest
```

`test_pdf_knowledge.py` builds a tiny PDF in memory, so the tests do not depend on the real brochure.

Note: the extraction rules in `knowledge.py` rely on the site's CSS classes (`faq-item`, `svc-kicker`, `mobile-menu`, ...).
A site redesign can change the output without an error, so check `knowledge/manifest.json` after each crawl.

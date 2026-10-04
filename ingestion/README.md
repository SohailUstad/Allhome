# Ingestion

Turns the ColourCoats website into text chunks for the chat assistant's knowledge base.
Python 3.9+, standard library only (nothing to install).

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

3. Package the chunks for the Spring app's ingestion endpoint:

   ```
   python package_knowledge.py               # knowledge/chunks.jsonl -> colourcoats-knowledge.zip
   ```

   Validates every chunk (`id`, `document_id`, `text`, `url` present; unique ids; at most 8000 bytes of text)
   and writes a zip with `manifest.json` and `chunks.jsonl`. Options: `--chunks`, `--output`, `--dataset-id`,
   `--dataset-version` (default: today), `--source`.

Generated files are git-ignored; rerun the scripts to rebuild them.

## Tests

```
python -m unittest
```

Note: the extraction rules in `knowledge.py` rely on the site's CSS classes (`faq-item`, `svc-kicker`, `mobile-menu`, ...).
A site redesign can change the output without an error, so check `knowledge/manifest.json` after each crawl.

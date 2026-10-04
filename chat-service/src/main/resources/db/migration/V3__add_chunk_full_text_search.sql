-- Keyword search over chunk text, alongside vector search. Catches exact names that embeddings handle poorly
-- (product names such as Marmorino, places such as Tiljala). PostgreSQL computes the column on every insert or
-- update; the 'english' configuration drops stop words and reduces words to their stem.
ALTER TABLE knowledge_chunk
    ADD COLUMN search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', text)) STORED;

CREATE INDEX knowledge_chunk_search_vector_idx ON knowledge_chunk USING gin (search_vector);

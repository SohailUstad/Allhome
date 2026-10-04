-- Vector similarity search for the knowledge base. Needs the pgvector extension on the server
-- (the pgvector/pgvector images and Cloud SQL for PostgreSQL both provide it).
CREATE EXTENSION IF NOT EXISTS vector;

# ColourCoats consultation assistant

A chat assistant for ColourCoats (https://www.colourcoats.com) that answers visitors from the company's own
website content and hands interested visitors to the team.

## Repository layout

| Path | What it is |
|---|---|
| `ingestion/` | Python scripts that crawl the website and package its content as knowledge chunks. See [ingestion/README.md](ingestion/README.md). |
| `chat-service/` | Spring Boot 4 service (Java 21, PostgreSQL + pgvector, JPA, Flyway). |
| `compose.yaml` | Local PostgreSQL with pgvector for development. |
| `.env.example` | Settings the service reads, with local defaults. |
| `.github/workflows/ci.yml` | CI: runs all tests on every pull request and on `main`. |

## Prerequisites

- Java 21
- Python 3.9+ (ingestion only)
- Podman or Docker, used for the local database and by the tests (Testcontainers).
  With Podman, start the machine first: `podman machine start`.
  `podman compose` also needs a compose provider, e.g. `pip install podman-compose`.

## Run locally

```
podman compose up -d              # or: docker compose up -d   (PostgreSQL on localhost:5433)
cd chat-service
./mvnw spring-boot:run            # Windows: .\mvnw.cmd spring-boot:run
```

Check it: http://localhost:8080/actuator/health returns `{"status":"UP"}`.
Settings come from environment variables (see `.env.example`); the defaults match `compose.yaml`.
Database changes are Flyway migrations in `chat-service/src/main/resources/db/migration`, applied on startup.

Stop the database with `podman compose down` (add `-v` to delete its data).

## Load knowledge

Build the archive with the ingestion scripts (see [ingestion/README.md](ingestion/README.md)), then upload it.
The service must run with `OPENAI_API_KEY` (embeddings) and `OPERATOR_PASSWORD` (login) set.

```
curl -u operator:<password> -F "file=@colourcoats-knowledge.zip" http://localhost:8080/api/ingestions
```

Every upload is stored as a version of its dataset (`dataset_id` / `dataset_version` from the archive's manifest)
and becomes the active version, the only one used for answers. Uploading the same version again is a no-op if the
content is identical and rejected (409) otherwise, so give each new crawl a new `--dataset-version`.

| Request | Purpose |
|---|---|
| `POST /api/ingestions` (form field `file`) | Upload an archive: `201` stored and active, `200` already stored, `400` invalid archive, `409` version exists with different content |
| `GET /api/ingestions[?datasetId=...]` | List stored versions, newest first |
| `POST /api/ingestions/{runId}/activate` | Make an earlier version active again (rollback, no re-embedding) |
| `GET /api/search?q=...` | Show which knowledge chunks the assistant would use for a question, with similarity and whether vector search, keyword search or both found them |

All `/api/ingestions` and `/api/search` requests need the operator login; only `/actuator/health` is public.

Search is hybrid: vector search (closeness in meaning) plus PostgreSQL full-text search (shared words, which catches
exact product and place names), merged with Reciprocal Rank Fusion, over the active versions only. Tuning lives under
`retrieval.*` in `application.yaml` (`top-k`, `candidates`, `min-similarity`).

## Chat

The chat code (prompt in `chat-service/src/main/resources/prompts/sales-system.md`) is copied from the prototype
and answers from the active knowledge via the hybrid search above. It needs `OPENAI_API_KEY`
(model `gpt-4.1-mini`, override with `OPENAI_CHAT_MODEL`).

| Request | Purpose |
|---|---|
| `POST /api/chat` `{"message": "...", "conversationId": "<from the previous reply>", "channel": "WEB_CHAT"}` | Public. Omit `conversationId` to start a conversation. Returns the reply, whether it was handed to a human, and the knowledge sources used. |
| `GET /api/chat/{conversationId}/messages` | Operator login. The conversation's transcript. |

Every request gets an `X-Request-Id`. The flow log (`logs/chat-service.jsonl`, logger `flow`) records each chat turn's
prompt and model response with that id and the conversation id. It contains personal data; `logging.level.flow: OFF`
turns it off.

## Tests

```
cd chat-service && ./mvnw verify     # starts a throwaway pgvector container; no local database needed
cd ingestion && python -m unittest
```

## Workflow

`main` is protected: every change goes through a pull request and needs both CI checks to pass.

1. `git switch main && git pull`
2. `git switch -c feature/<name>` (or `fix/...`, `chore/...`)
3. Commit, then `git push -u origin <branch>` and open a pull request
4. Merge when CI is green, then `git switch main && git pull && git branch -d <branch>`

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

All `/api/ingestions` requests need the operator login; only `/actuator/health` is public.

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

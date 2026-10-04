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

# ColourCoats AI Lead Agent

A lead qualification agent for ColourCoats (https://www.colourcoats.com). Visitors chat through **Zoho SalesIQ**
on the website or by **Instagram DM**; the agent answers from ColourCoats' own website and brochure, qualifies the
visitor naturally, and hands the sales team a structured lead. SalesIQ is only the channel; the agent lives in this
repository: versioned, observable and testable.

| Live demo | |
|---|---|
| Demo website (SalesIQ widget + direct chat panel) | https://chat-service-343129434945.asia-south1.run.app |
| Lead store (operator login) | https://chat-service-343129434945.asia-south1.run.app/leads |
| Running version | https://chat-service-343129434945.asia-south1.run.app/actuator/info |
| SalesIQ webhook | `https://chat-service-343129434945.asia-south1.run.app/api/salesiq/webhook` |

**Contents:** [Architecture](#architecture) · [Setup](#setup) · [SalesIQ integration](#salesiq-integration) ·
[Knowledge base](#knowledge-base) · [Agent and configuration](#agent-and-configuration) ·
[Lead qualification](#lead-qualification) · [Observability](#observability-and-debugging) · [Evals](#evals) ·
[API reference](#api-reference) · [Development workflow](#development-workflow) · [Limitations](#limitations) ·
[Future improvements](#future-improvements)

## Architecture

```
 ┌──────────── Channel layer: Zoho SalesIQ ────────────┐        ┌──────── Agent layer: chat-service (Spring Boot, Cloud Run) ────────┐
 │  Website widget ─┐                                   │  HTTPS │  SalesIQ webhook  ── adapts SalesIQ's format, 3.8 s deadline,       │
 │  Instagram DM  ──┴─► Zobot ── webhook (JSON) ────────┼───────►│                      forward to operator on handoff                  │
 │  Operators  ◄── chats forwarded on handoff           │        │  Direct chat API  ── the demo site's own panel                      │
 └──────────────────────────────────────────────────────┘        │        │                                                            │
                                                                  │        ▼                                                            │
                                                                  │  ChatService ── prompt + history + lead profile + knowledge ──►     │
                                                                  │        │         OpenAI gpt-4.1-mini (structured JSON reply)        │
                                                                  │        ├── KnowledgeSearch: vector + keyword search (pgvector)      │
                                                                  │        ├── HandoffPolicy: safety-net rules                         │
                                                                  │        └── leads, transcripts, handoffs ──► PostgreSQL (Cloud SQL)  │
                                                                  │  Lead console /leads · ingestion API · search API · flow log         │
                                                                  └──────────────────────────────────────────────────────────────────────┘
 ingestion/ (Python, offline): website + brochure PDF ──► chunks ──► knowledge zip ──► POST /api/ingestions ──► embeddings in pgvector
```

**Separation of concerns.** SalesIQ only delivers messages and hosts the operators. The webhook
(`salesiq/SalesIqWebhookController`) translates SalesIQ's request/response format and its 5-second limit; all agent
behaviour (prompt, retrieval, qualification, handoff) is in `chat/ChatService`, which the website's direct chat
panel uses as well. Replacing SalesIQ would mean replacing only the webhook adapter.

| Path | What it is |
|---|---|
| `chat-service/` | Spring Boot 4 service: Java 21, PostgreSQL 17 + pgvector, JPA, Flyway, Spring AI 2 (OpenAI) |
| `chat-service/src/main/resources/prompts/sales-system.md` | The agent's first system prompt; afterwards versioned in the database (`prompt_version`) |
| `chat-service/src/main/resources/application.yaml` | All tunable settings (model, retrieval, SalesIQ, deadlines) |
| `chat-service/src/main/resources/db/migration/` | Database schema as versioned Flyway migrations |
| `chat-service/src/test/resources/evals/cases.json` | The eval cases |
| `ingestion/` | Python scripts: crawl the site, extract the brochure PDF, chunk and package knowledge ([README](ingestion/README.md)) |
| `deploy/cloud-run.md` | Every command used to deploy to Google Cloud Run + Cloud SQL |
| `compose.yaml`, `.env.example` | Local database and the settings the service reads |
| `.github/workflows/ci.yml` | CI: all tests on every pull request and on `main` |

Package overview (`com.allhome.colourcoats`): `ingestion` (knowledge upload and versions), `retrieval` (hybrid
search), `chat` (agent, leads, transcripts), `prompt` (versioned system prompt), `salesiq` (webhook), `operator` (lead console), `web` (demo site),
`security`, `flowlog` (request ids and the flow log).

## Setup

**Prerequisites:** Java 21; Python 3.9+ (ingestion only); Podman or Docker (local database and Testcontainers
tests). With Podman, run `podman machine start` first; `podman compose` needs a provider such as
`pip install podman-compose`.

**Run locally**

```
podman compose up -d                       # PostgreSQL 17 + pgvector on localhost:5433 (or: docker compose up -d)
cd chat-service
$env:OPENAI_API_KEY = '<key>'              # bash: export OPENAI_API_KEY=<key>
$env:OPERATOR_PASSWORD = '<choose one>'
./mvnw spring-boot:run                     # Windows: .\mvnw.cmd spring-boot:run
```

Open http://localhost:8080 (demo site) and http://localhost:8080/leads (operator login). All settings come from
environment variables listed in `.env.example`; the defaults match `compose.yaml`. Flyway applies the schema on
startup.

**Load knowledge** (the database starts empty; the agent then answers "I don't know"):

```
cd ingestion
python -m pip install -r requirements.txt
python list_pages.py                                   # crawl colourcoats.com
python list_pages.py --stage knowledge                 # website chunks
python pdf_knowledge.py <path>/colourcoats-brochure-2026.pdf --url https://www.colourcoats.com/brochure-2026.pdf --title "ColourCoats Brochure 2026"
python package_knowledge.py --chunks knowledge/chunks.jsonl knowledge/pdf_chunks.jsonl --dataset-version <new version>
curl -u operator:<password> -F "file=@colourcoats-knowledge.zip" http://localhost:8080/api/ingestions
```

**Connect SalesIQ:** set the Zobot's webhook URL to `https://<host>/api/salesiq/webhook` (for a local run, expose
port 8080 with a tunnel such as ngrok). Assign the same Zobot to the website widget and to the connected Instagram
account.

**Deploy:** see [deploy/cloud-run.md](deploy/cloud-run.md): Cloud Build image, Cloud Run (Mumbai) and Cloud SQL,
secrets in Secret Manager, every command recorded.

## SalesIQ integration

- **One Zobot, one webhook, two channels.** The webhook reads `visitor.channel`: `Instagram` becomes channel
  `INSTAGRAM`, anything else `ZOHO_SALESIQ`. The prompt adapts (e.g. shorter replies and no requests for an
  Instagram handle on Instagram).
- **Conversation identity.** SalesIQ's `active_conversation_id` (falling back to the visitor id) is turned into a
  stable conversation id, so every turn of a chat lands in the same transcript and lead.
- **Greeting.** Calls that are not visitor messages (the `trigger` when a chat opens, SalesIQ's validation ping)
  get the configured opener (`salesiq.opener`).
- **5-second limit.** SalesIQ waits at most 5 s. The agent runs on a worker thread with a 3.8 s deadline
  (`salesiq.response-deadline-ms`); if it is late or fails, the visitor gets a holding message, the conversation is
  queued for a human, and SalesIQ always receives a valid response. A late answer can never be saved afterwards.
- **Handoff = live transfer.** When the agent hands off, the webhook answers with SalesIQ's `forward` action (optional
  `SALESIQ_DEPARTMENT_ID`), and the reply is stripped of questions (the bot will not be there to read the answer).
  The AI never answers that conversation again; if no operator picks it up, SalesIQ calls the bot again and the
  visitor gets SalesIQ's "operators busy, leave a message" flow.
- **Pre-chat details.** Name, email and phone from SalesIQ's pre-chat form are copied into the lead (placeholder
  names such as "Visitor 51234" are ignored; SalesIQ's geo-IP city is not used as the project city).
- **Signatures.** `SalesIqSignatureVerifier` checks SalesIQ's RSA signature (`x-siqsignature`) when
  `SALESIQ_PUBLIC_KEYS` is set (two keys supported for rotation). It is not enabled in the demo; see Limitations.

## Knowledge base

**Sources.** The colourcoats.com pages (crawled) and the 2026 brochure PDF (its text layer). The two product
catalogues (textures, wood coatings) are image-only PDFs and are not included.

**Ingestion (offline, Python).** The crawler respects `robots.txt` and archives raw HTML. Extraction removes
navigation, scripts, pop-ups and forms, and splits pages along their own structure: headings, paragraphs, list
items, FAQ question + answer, stats, contacts. Blocks under the same heading are packed into chunks of at most 300
words, never mixing sections; each chunk starts with the page title and heading path ("Lime Wash > Where it
works"), so short passages keep their context, and keeps the exact section URL (`#svc-limewash`) for citations. The
brochure's pages are packed the same way and cite `brochure-2026.pdf#page=N`.

**Storage and versions.** Chunks are uploaded as a zip, validated completely before anything is stored, embedded with
OpenAI `text-embedding-3-small` (1536 dimensions) and stored in PostgreSQL with pgvector. Every upload is kept as a
version of its dataset; exactly one version per dataset is active (enforced by the database) and only the active
one is searched. Uploading identical content again is free (detected by SHA-256); rolling back is one call.

**Retrieval.** Hybrid search over the active versions: vector search (closeness in meaning, HNSW index, cosine) and
PostgreSQL full-text search (shared words, which catches product and place names such as Marmorino or Tiljala that
embeddings rank low), merged with Reciprocal Rank Fusion. Up to 6 chunks reach the model; vector matches below
cosine 0.3 are dropped as unrelated. pgvector's iterative index scan keeps results complete even when many older
versions are stored. The query is the visitor's message plus their previous message, so follow-ups ("how long does
it take?") keep their topic.

**Grounding.** The prompt allows ColourCoats facts only from the retrieved knowledge: no prices, durations,
warranties, financing or product suitability that the knowledge does not state, and "I don't know" plus an offer
of a specialist when it is missing. Evals check this, including an LLM judge that must quote evidence for every
claim.

**Trade-offs.** Structure-aware chunking fits a well-structured marketing site and needs no AI calls, but depends on
the site's HTML classes (a redesign can silently change extraction). Hybrid search adds one SQL query but fixes the
weakness of embeddings on exact names. Keeping every version costs storage and needs the active filter on every
search, but makes rollback instant and auditable.

## Agent and configuration

Each message: the stored conversation history (last 20 messages), the lead profile so far, the channel and the
retrieved knowledge go to `gpt-4.1-mini` (temperature 0.4, at most 600 output tokens to stay within SalesIQ's time
limit). The model returns one JSON object: the reply, a handoff flag and reason, persona, intent and the lead details
it heard. The reply, lead and handoff request are saved in one transaction; an unparseable model response is never
shown and is routed to a human instead.

How the agent is configured and changed:

| What | Where | How a change is made and verified |
|---|---|---|
| Behaviour (tone, grounding, qualification, handoff rules) | System prompt versions in the database (`prompt_version`); version 1 is `prompts/sales-system.md` | Console page **Agent prompt** (`/prompts`): the prompt in sections, edit one section into a draft (locked and protected sections enforced), see the diff, chat with the draft in the **Try version** panel (real knowledge and model, nothing stored), activate; rollback = activate an earlier version. Before activating: `./mvnw test -Pevals -Deval.prompt=<draft id>` |
| Handoff safety net (explicit requests for a person or call, complaints) | `chat/HandoffPolicy.java` | Unit tests (`HandoffPolicyTests`) |
| Live chat model | Console page **Agent model**; allowed models in `live-model.models` (fast non-reasoning models only: SalesIQ allows 3.8 s); `OPENAI_CHAT_MODEL` is the starting default | The page warns that every live conversation is affected from its next message, requires a reason (15+ characters) and a confirmation, and keeps the history (`live_model_change`). Run the evals with the new model first |
| Retrieval sizes, deadlines, SalesIQ messages | `application.yaml` (most also as environment variables) | Release and deploy |
| Knowledge | Uploaded versions | `/api/search` to inspect; activate an earlier version to roll back |

Every release is a git tag; the deployed commit is shown at `/actuator/info`.

**Changing the prompt with AI.** On the **Agent prompt** page the operator can describe a behaviour change in plain
language ("with architects, use precise finish terms") instead of editing the text:

1. Choose a section (or let the AI choose), a model and, for reasoning models, the reasoning effort. The models on
   offer are configured under `prompt-editor.models`; models the OpenAI key cannot use are shown disabled. Standard
   models run at temperature 0, reasoning models with the chosen effort. Visitors are always answered by the chat
   model; the editor model only proposes prompt changes.
2. The editor model follows its own fixed instructions (`prompts/prompt-editor.md`, in git) and answers with JSON: the
   changed sections only (complete new text), a reason for each, conflicts with other sections, or a question back.
   It refuses ColourCoats facts (they belong in the knowledge base) and requests that need code.
3. The code checks the proposal: changes to locked or unknown sections, empty sections and changes that change
   nothing are dropped and reported. The page shows a line diff for each changed section.
4. The operator accepts it into a draft (protected sections need a confirmation), refines it with feedback (the
   previous proposal goes back to the model), or discards it. A proposal made for a version that has changed since
   can no longer be accepted.
5. The draft is activated as usual. Every request is stored in `prompt_edit` with the instruction, model, effort,
   proposal, token counts (including reasoning tokens), duration, outcome and the draft it produced.

The request waits for the model (up to `prompt-editor.timeout`, 170 s): on Cloud Run the CPU is only allocated while a
request runs, so a background job would stall.

## Lead qualification

**Captured fields:** name, phone (WhatsApp ok), email, city, project type, spaces, finish interest, area size,
timeline, budget, callback time, plus persona and intent. The agent learns them naturally over the conversation (at
most one question per reply, no checklist), never re-asks known details, never infers what was not said, and only
asks for contact details when there is a reason to follow up.

**Persona:** HOMEOWNER, ARCHITECT_OR_DESIGNER, BUILDER_OR_DEVELOPER, COMMERCIAL_CLIENT, EXISTING_CUSTOMER,
PARTNER_PROSPECT, VENDOR_OR_JOB_SEEKER, UNKNOWN. **Intent:** BROWSING, RESEARCHING, PLANNING_PROJECT,
READY_TO_ENGAGE, SUPPORT, UNKNOWN. Operators can correct the persona in the lead console.

**Status:**
- `QUALIFIED`: name, phone, city, a known persona and a requirement (project type, spaces or finish interest),
  i.e. the minimum a sales rep needs;
- `ENGAGED`: any of these details;
- `NEW`: nothing yet.

**Merging:** each turn's details are merged into the stored lead: new non-empty values win, empty values never erase
known ones, and an UNKNOWN or invalid persona never overwrites a known one.

**Handoff (continue → offer → hand off):** the agent keeps helping by default and offers a specialist when human
input would help; it hands off when the visitor asks for a person or call, accepts an offer ("yes", "haan", "theek
hai"), asks for something only staff can do (a quote, a site visit, a partnership), or has a complaint about
ColourCoats' work. A price or timeline *question* alone is answered honestly without inventing numbers, not handed
off. `HandoffPolicy` forces a handoff for unmistakable requests even if the model misses them.

**Lead store:** PostgreSQL (`chat_conversation`, `chat_message`, `chat_lead`), shown in the lead console at
`/leads`: filters (status, persona, intent, channel, follow-up, dates, text), status counts, lead detail with the
full transcript and handoff reason, a "handled" marker, and CSV export.

## Observability and debugging

- **Request ids.** Every HTTP request gets an id, returned as `X-Request-Id`.
- **Flow log.** One entry per step of each message, as JSON lines (logger `flow`): `salesiq.request` (the exact
  SalesIQ payload), `openai.request` (the full prompt, the model requested with the `model_change_id` that made it
  live, the prompt version and settings), `openai.response` (raw model output, the model OpenAI reports, tokens,
  duration), `salesiq.response` (what SalesIQ received, total duration). Each stored reply also keeps its model, prompt
  version and model change, shown under the reply in the lead console transcript ("gpt-4.1-mini · prompt v3"). Every entry carries `request_id`,
  `conversation_id` and `turn_id`. On Cloud Run it is in Cloud Logging (filter `jsonPayload.request_id`); locally in
  `chat-service/logs/chat-service.jsonl`. It contains personal data; `logging.level.flow: OFF` turns it off.
- **Conversation view.** `/leads/{id}` shows a conversation's transcript, lead and handoff reason.
- **Retrieval view.** `GET /api/search?q=...` shows exactly which chunks the agent would get for a question and why.
- **Version.** `/actuator/info` shows the deployed version and commit; `/actuator/health` the health.

## Evals

Live behaviour evals run the real agent (real retrieval from the database, real OpenAI) through 36 scripted
conversations and check each reply automatically. Eval conversations use the agent's no-storage path and never
become conversations or leads.

**In the console (Evals page):**
- **Cases** are stored in the database (`eval_case`, loaded from `evals/cases.json` on first start) and edited in the
  console: visitor messages one per line, expectations as JSON, critical and enabled flags; who changed what is kept.
- **Run** a prompt version (usually a draft) with any allowed live model, with or without the grounding judge, on all
  enabled cases or only the critical ones. The page runs the cases one request at a time (a case takes 5–30 s), so it
  works within Cloud Run's request model; closing the page pauses the run, opening it continues.
- **Report:** cases passed, pass rate, critical failures, results by category, and each case with its checks and the
  full conversation. **Compare with** another run marks every case whose result changed. Each result keeps the case as
  it was run, so old reports stay correct after a case is edited.
- **Activation gate:** a draft can only be activated after a full run on it **with the live model** passed at least
  `EVAL_ACTIVATION_MIN_PASS_RATE` of the cases (default `0.85`; behaviour checks count, the noisy judge and style checks
  do not; `0` switches the gate off). The prompt page says what is missing. Rolling back to an earlier version is never
  blocked.

**From the command line** (same runner and cases):

```
cd chat-service
./mvnw test -Pevals                              # all cases; report in target/evals/latest.md (+ latest.json)
./mvnw test -Pevals -Deval.case=pricing-handoff  # one case
./mvnw test -Pevals -Deval.prompt=<version id>   # a prompt draft before activating it (default: active; file: the git file)
# options: -Deval.repeat=3  -Deval.judge=false  -Deval.minPassRate=0.85  -Deval.model=gpt-4.1
```

The `evals` Maven profile runs only `ChatEvalTests` (skipped in normal builds, so CI needs no API key). The database
it reads must have knowledge loaded.

**What is checked:** handoff yes/no; persona and intent; lead fields and status; retrieved sources; required content;
forbidden content in any reply (invented prices, durations, warranties, unsupported claims, prompt leaks); style on
every reply (at most 60 words and one question); Instagram reply length; and a grounding judge (`gpt-4.1`) that must
quote supporting knowledge for each factual claim, verified in code.

**Cases** cover the areas the brief asks for: grounding (6), pricing and timeline (6), unknown questions (2), visitor
types and personas (8), handoff and callback (6), Instagram channel (1), incomplete leads (4), conversation style
(2) and prompt injection (1). 14 are critical.

**Gate:** every critical case must pass its deterministic checks (the judge and style checks are too noisy to gate
on) and at least 85% of all checks must pass; otherwise the build fails. A free check of the case file itself
(`EvalCasesTests`) runs in every build. The workflow for a change: change the prompt or code on a branch, run
`-Pevals`, compare the reports, merge only if nothing important regressed.

## API reference

| Request | Access | Purpose |
|---|---|---|
| `POST /api/salesiq/webhook` | Public (SalesIQ) | Zobot webhook for website chat and Instagram DMs |
| `POST /api/chat` `{"message", "conversationId"?, "channel"?}` | Public | Direct chat (the demo site's panel); omit `conversationId` to start |
| `GET /api/chat/{conversationId}/messages` | Operator | Transcript |
| `POST /api/ingestions` (form field `file`) | Operator | Upload knowledge: `201` stored and active, `200` already stored, `400` invalid, `409` version exists with different content |
| `GET /api/ingestions[?datasetId=]` | Operator | Stored knowledge versions |
| `POST /api/ingestions/{runId}/activate` | Operator | Roll back to an earlier version |
| `GET /api/search?q=` | Operator | What the agent would retrieve |
| `GET /api/prompts`, `GET /api/prompts/active`, `GET /api/prompts/{id}` | Operator | System prompt versions, with their sections and locks |
| `POST /api/prompts` `{"sections":{"<key>":"<text>"}, "note", "confirmProtected"}`, `PATCH /api/prompts/{id}` | Operator | Start a draft from the active version / change a draft: `400` if a section is locked, empty, or protected without confirmation |
| `POST /api/prompts/{id}/activate`, `POST /api/prompts/{id}/discard` | Operator | Make a version active, also for rollback (`409` if the active version changed since the draft was started) / throw a draft away |
| `/`, `/leads`, `/leads/export.csv`, `/prompts` | Public / operator | Demo site, lead console, CSV export, agent prompt editor |
| `/agent-model`, `/evals`, `/evals/cases`, `/evals/runs/{id}` | Operator | Live model (change with reason, history), eval runs and reports, eval cases |
| `POST /prompts/{versionId}/ai-edits`, `POST /prompts/ai-edits/{id}/refine` (JSON), `.../accept`, `.../discard` | Operator (console session, CSRF) | AI prompt editor: ask, refine, accept into a draft, discard |
| `POST /prompts/{versionId}/test-chat` `{"message", "channel", "history", "lead"}` | Operator (console session, CSRF) | Test chat with any prompt version; the browser keeps the conversation, nothing is stored |
| `/actuator/health`, `/actuator/info` | Public | Health, running version |

The operator logs in with `OPERATOR_USERNAME` / `OPERATOR_PASSWORD` (form login in the browser, HTTP Basic for
scripts); without a password nobody can log in.

## Development workflow

```
cd chat-service && ./mvnw verify       # 100+ tests against a throwaway pgvector container (Testcontainers); no API calls
cd ingestion && python -m unittest
```

`main` is protected: every change goes through a pull request and needs both CI checks (Python, Spring Boot) to
pass. Releases are git tags; the deployed image is tagged with the release and the commit.

1. `git switch main && git pull`, then `git switch -c feature/<name>` (or `fix/...`, `chore/...`, `docs/...`)
2. Commit, `git push -u origin <branch>`, open a pull request
3. Merge (merge commit) when CI is green; `git switch main && git pull && git branch -d <branch>`

## Limitations

- **Webhook signatures are not enforced in the demo** (`SALESIQ_PUBLIC_KEYS` is empty). The check is implemented and
  unit-tested; enabling it safely means a no-traffic Cloud Run revision first.
- **Image-only PDFs are skipped**: the textures and wood-coatings catalogues need OCR or a vision model.
- **Synchronous webhook only.** An answer that takes longer than 3.8 s becomes a holding message and a handoff.
- **A handoff is one-way.** The AI never re-enters a forwarded conversation; with no operator online the visitor gets
  SalesIQ's leave-a-message flow, and the agent does not know whether specialists are online.
- **SalesIQ failure events** (e.g. forward failed, operators unavailable) are answered with the greeting.
- **Text only.** Attachments and voice notes get "please type your question".
- **Known agent issues** carried over from the prototype: in SalesIQ chats the current message also appears in the
  history, which weakens follow-up search; the agent sometimes asks for a phone number right before a live
  transfer; some replies exceed 60 words; long English history can pull a Hinglish reply into English.
- **Prompt changes are not reviewed in a pull request** once the prompt lives in the database; drafts, evals on a
  draft and one-call rollback take the place of that review.
- **AI prompt editor:** the OpenAI client retries a failed call (up to 3 times), so a failing request can take longer
  than `prompt-editor.timeout`; requests are not rate-limited per operator, and the editor's proposals are only as
  good as the chosen model: the diff review, the draft and evals are the safety net.
- **Knowledge refresh is manual** (crawl, package, upload).
- **Demo-sized infrastructure:** one Cloud Run instance, smallest Cloud SQL tier without backups, one operator
  account, no rate limiting.
- **Evals cost money and run on demand**, not in CI; the LLM judge is noisy, so it does not gate.
- **The flow log holds personal data**; retention follows the logging platform's defaults.

## Future improvements

- Fix the known agent issues above, each with an eval run before and after.
- Enable webhook signature verification through a no-traffic revision, then promote.
- OCR or vision-model transcription for the image-only catalogues.
- Async ("pending") replies for slow website answers; pass operator availability to the agent; handle SalesIQ failure
  events (1001/1002/1007) with an offline flow instead of the greeting.
- Push qualified leads to Zoho CRM (or email/WhatsApp alerts) instead of only the console and CSV.
- Scheduled re-crawl that uploads a new knowledge version only when content changed.
- Nightly evals in CI with the API key as a secret, and a trend of pass rates per prompt version.
- Production hardening: autoscaling, Cloud SQL backups and private IP, multiple operator accounts and roles, rate
  limiting, data retention for transcripts and logs.

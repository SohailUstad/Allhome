# Improvement plan

Planned improvements after release `v0.1.2`, in the recommended order. Nothing here is built yet. Each numbered step
is meant to be one branch and one pull request. Every change to the prompt or the conversation is checked with an eval
run before and after (`./mvnw test -Pevals`), and the two reports are compared.

**Before starting:** run the evals once with the knowledge loaded, to get a baseline report that later runs can be
compared against.

## 1. Editable system prompt (first priority)

### Problem

The system prompt is a file inside the jar (`prompts/sales-system.md`, about 14 KB). Changing one sentence means a pull
request, CI, a tag, a Cloud Build image and a Cloud Run deploy (about 10–15 minutes, plus a person who can run them).

### Design: versioned prompts in PostgreSQL, the same way knowledge is versioned

| Part | Design |
|---|---|
| Storage | New table `prompt_version` (Flyway V5): `id`, `name` (`sales-system`), `content`, `sha256`, `note`, `created_by`, `created_at`, `active`. A partial unique index allows exactly one active version per name, the same rule as `ingestion_run`. Every version is kept. |
| First version | On startup, if no version exists, the current `sales-system.md` is stored as version 1 and activated. The file stays in git as the default and the fallback. |
| Changing it | Operator-only API: `GET /api/prompts` (versions), `GET /api/prompts/{id}`, `POST /api/prompts` (new version, **not** active), `POST /api/prompts/{id}/activate`, and rollback by activating an earlier version. Saving identical content again returns the existing version (SHA-256), as for knowledge. Optional: a `/prompts` page in the console with an editor and a diff against the active version. |
| Use | `ChatService` asks a `PromptStore` for the active prompt on every message, instead of reading the file once at startup. |
| Traceability | The prompt version id is written to the flow log (`openai.request`) and stored on every assistant message (new column `chat_message.prompt_version`). Every reply can be traced to the exact prompt that produced it. |
| Safety | Content must not be blank, with a size limit (e.g. 50 KB). The JSON output format is still added by the code, so a prompt edit cannot break reply parsing. Only the operator role can change prompts. |
| Evals | `-Deval.promptVersion=<id>` runs the evals against a version that is **not yet active**. Workflow: create the version, run the evals on it, compare with the active version's report, and activate only if nothing important got worse. |

**Trade-off:** prompt changes no longer go through a pull request. This is mitigated by every version being stored
with its author, time and note, by evals before activation, and by one-call rollback. Changes that need code (new
fields, new rules in `HandoffPolicy`) still go through the normal release.

### Caching the prompt

The active prompt is read on every visitor message, so it should not cost a database query each time.

**Recommendation: an in-memory cache inside the app, with PostgreSQL as the source of truth. No Redis.**

- The prompt is about 14 KB and changes rarely. Reading it from memory takes no time; reading it from Redis means a
  network call on every message.
- The demo runs one Cloud Run instance (`--max-instances 1`). Activating a version updates that instance's cache
  straight away.
- With more instances later, each one re-checks the active version id every 30 seconds (a single-row query), or listens
  for PostgreSQL `LISTEN/NOTIFY` on activation. A change reaches every instance within seconds, still without Redis.

### If a Redis instance is wanted anyway: options on GCP

| Option | Free? | Fits Cloud Run in `asia-south1`? | Notes |
|---|---|---|---|
| **In-app memory cache** (recommended) | Yes | Yes | No new service. Each instance has its own copy, which is fine for a prompt. |
| **Redis as a Cloud Run sidecar** (a second container in the same service) | Yes (only the instance's CPU and memory) | Yes, reached on `localhost:6379` | Its data is per instance and lost on restart, just like in-app memory, so it adds a moving part without adding sharing. Useful only to demonstrate a Redis integration. |
| **Memorystore for Redis / Valkey** (GCP's managed Redis) | **No.** There is no free tier; the smallest instance is billed every hour, from creation until deleted. | Yes, needs VPC access from Cloud Run (Direct VPC egress) | The right choice in production if several instances must share data. |
| **Redis on a Compute Engine `e2-micro`** (Always Free) | Free only in `us-west1`, `us-central1`, `us-east1` | No: the US regions add a cross-region round trip that would be slower than reading from PostgreSQL | A self-managed VM to patch and keep running. |
| **Firestore** (Always Free: 1 GiB, 50,000 reads a day) | Yes, within limits | Yes | A document database, not a cache. It would duplicate what PostgreSQL already does here. |
| **Hosted Redis outside GCP** (e.g. Redis Cloud or Upstash free plans) | Free plans exist (small size limits) | Depends on the regions the plan offers; check for Mumbai | Another account and a credential to store in Secret Manager. |

Redis becomes worth adding when several instances need to **share** fast-changing data, for example:

- rate limiting per visitor;
- de-duplicating SalesIQ retries;
- caching embeddings for repeated questions;
- conversation locks.

That is a scaling step, not something the prompt needs.

## 2. Conversation fixes (Phase A)

| # | Change | Why | Verified by |
|---|---|---|---|
| A1 | Stop adding the visitor's current message to the history twice in SalesIQ chats | A real bug that weakens follow-up search and can make replies repeat themselves | A webhook test on the exact messages sent to the model |
| A2 | Detect the visitor's language in code (Devanagari, Roman Hinglish or English) and pass it as a hint | Hinglish replies drift into English after English history | New evals: a Hinglish message after English history |
| A3 | Pass `specialists_online` to the model, from SalesIQ's operator status if the payload has it, otherwise from the configured operator hours | Removes the "ask for a phone number, then transfer" contradiction | Handoff evals with operators online and offline |
| A4 | Length guard in code: a reply over the limit is sent back to the model once with "shorten to 45 words or fewer", or its extra sentences are cut | "Short replies" becomes a guarantee, not a hope | The existing style checks become strict |

## 3. Lead management (Phase C)

| # | Change |
|---|---|
| C1 | **Pipeline stage**, set by the sales team: New → Contacted → Site visit → Quoted → Won / Lost (with a lost reason). It is separate from the computed qualification status and replaces the "handled" toggle. |
| C2 | **Notes and a follow-up date** on each lead, with an "overdue follow-ups" filter. |
| C5 | **AI lead summary**: a 2–3 line brief for the sales rep, written when a lead becomes qualified or is handed off, and stored on the lead. |
| C3 | **Phone normalisation** to E.164 (`+91…`), keeping the original text for display. |
| C4 | **Duplicate detection**: leads with the same normalised phone or email are linked ("also chatted on Instagram, 2 conversations"). |
| C6 | **Priority** (Hot, Warm, Cold) from fixed rules in code: intent, persona, completeness and timeline. Rules are explainable and testable, unlike a model score. |
| C7 | **An email alert** when a lead becomes qualified or asks for a callback. Later: push leads to Zoho CRM. |

Schema changes are new Flyway migrations, and existing data is kept.

## 4. Prompt quality (Phase B)

The prompt rewrite comes after the evals have a reliable baseline, because it changes behaviour the most. With step 1
in place, each of these is a new prompt version that is evaluated before it is activated.

| # | Change | Why |
|---|---|---|
| B1 | Restructure the prompt to about half its length: one handoff section, one output section, no repeated rules | Handoff is now spread over sections 8, 9, 10 and the examples. Fewer conflicts give a small model more consistent answers, and cost less. |
| B2 | The code works out the "next useful detail" from the lead (e.g. city is unknown and a visit was mentioned) and passes it as a hint; the model decides whether to ask | Questions follow the lead's state instead of the model's guess |
| B3 | Tone by persona: short and technical for architects and builders, warmer for homeowners, no selling to existing customers | Conversations sound more natural |
| B4 | One short right/wrong example for each persona and channel | Small models pick up style from examples better than from rules |

## 5. SalesIQ extras (Phase D, optional)

- **D1. Quick-reply suggestions** in SalesIQ replies ("Book a site visit", "Talk to a specialist", "See finishes").
  SalesIQ's suggestion format and its support on Instagram need to be confirmed first.
- **D2. An offline message for SalesIQ failure events** (forward failed, operators unavailable), instead of the
  greeting.

## Order

1 (editable prompt) → A1 → A2 → A3 → A4 → C1 → C2 → C5 → C3/C4 → B1–B4 → C6 → C7 → D

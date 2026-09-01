# Lexicon Scanner Service

Spring Boot 4 · Java 21 · Intel Hyperscan · GKE / Compute Engine

An **analyst-facing testing harness**: "test this lexicon rule against
sample messages." Upload lexicon terms (typed or CSV) plus a communication
message (typed or file), and the service:

1. Calls the **Lexicon Compile Service** to translate each Natural
   Language term into Hyperscan-valid pattern(s) plus a `resolvedPatterns`
   string describing any NEAR/FOLLOWEDBY/AND NOT structure — or, for
   Regex-type terms, validates the caller-supplied pattern directly with
   Hyperscan, with no Compile Service call at all.
2. For Natural Language terms, evaluates each PASS term's `resolvedPatterns`
   in plain Java regex against the message text — real NEAR/FOLLOWEDBY
   word-distance/order checking and AND NOT evaluation, with no Hyperscan
   involved at all. For Regex terms, compiles a **Hyperscan database**
   (one plain pattern per term — Regex terms never have AND NOT or
   NEAR/FOLLOWEDBY) once per scan session and scans with it (Java-regex
   fallback for environments where the native scratch allocator isn't
   available, e.g. some test runners/IDE debuggers).
3. Returns the **highlighted message** (HTML `<mark>` tags) and per-term
   match results, with disclaimer-aware alert suppression.

A built-in **micro-frontend web UI** is served at `/` — it can be embedded
in any host web application.

> **This project's correctness is dominated by the Lexicon Compile
> Service's response contract, not by anything local.** Before changing
> `Models.java`, `LexiconCompileClient.java`, `LexiconScanOrchestrator.java`,
> or the AND NOT/NEAR/FOLLOWEDBY evaluation (`ResolvedPatternTree`/
> `ResolvedPatternMatcher`), read `CLAUDE.md` — it documents the current
> verified contract with the Compile Service, the two genuinely separate
> scanning paths (Natural Language vs. Regex), and the exact spot (leaf-list
> concatenation for AND NOT terms) most likely to break again if this area
> is touched without re-reading it first.

---

## Project structure

```
src/
├── main/java/com/db/macs3/ecomms/spectre/
│   ├── LexiconScannerApp.java          — Spring Boot entry point
│   ├── config/
│   │   ├── AppProperties.java          — @ConfigurationProperties
│   │   ├── GzipRequestFilter.java      — GZIP decompression filter
│   │   └── RestClientConfig.java       — RestClient bean + CORS + static resources
│   ├── model/
│   │   ├── Models.java                 — All domain records (request / response / internal)
│   │   ├── ResolvedPatternTree.java    — parses resolvedPatterns + regexPattern/exclusionRegex
│   │   │                                 into an evaluable NEAR/FOLLOWEDBY/AND-NOT tree
│   │   ├── TermType.java               — NATURAL_LANGUAGE / REGEX
│   │   ├── TermSource.java             — typed terms vs. uploaded CSV
│   │   ├── MessageSource.java          — pasted text vs. one-or-more uploaded files
│   │   └── DisclaimerSource.java       — NONE / typed text / uploaded CSV
│   ├── client/
│   │   └── LexiconCompileClient.java   — HTTP client for Lexicon Compile Service
│   ├── service/
│   │   ├── CsvParserService.java          — OpenCSV-based CSV term/disclaimer parser
│   │   ├── HtmlStrippingService.java      — tag/whitespace stripping + offset map back to original text
│   │   ├── DisclaimerDetectionService.java— exact-substring disclaimer detection
│   │   ├── ResolvedPatternMatcher.java    — evaluates a ResolvedPatternTree against message text
│   │   │                                    (Natural Language terms — no Hyperscan involved)
│   │   ├── HyperscanScanService.java      — Hyperscan compile + scan (Regex terms only)
│   │   ├── MatchHighlightService.java     — byte-offset → char-index, HTML <mark> builder
│   │   └── LexiconScanOrchestrator.java   — full pipeline orchestration
│   └── controller/
│       ├── ScanController.java         — REST endpoints
│       └── GlobalExceptionHandler.java — domain exception → HTTP response
├── main/resources/
│   ├── application.yml                 — config (default, docker, cloud-run profiles)
│   └── static/index.html               — self-contained micro-frontend UI
└── test/java/com/db/macs3/ecomms/spectre/
    ├── service/     CsvParserServiceTest, HyperscanScanServiceTest,
    │                MatchHighlightServiceTest, LexiconScanOrchestratorTest,
    │                DisclaimerDetectionServiceTest, HtmlStrippingServiceTest
    ├── client/       LexiconCompileClientTest
    ├── controller/   ScanControllerTest
    └── integration/  MultiLanguageScanIntegrationTest
```

---

## REST API

| Method | Path               | Content-Type          | Description                            |
|--------|--------------------|------------------------|-----------------------------------------|
| POST   | `/api/scan`        | `multipart/form-data` | Primary endpoint (UI form submission)   |
| POST   | `/api/scan/json`   | `application/json`    | Programmatic / GZIP clients             |
| GET    | `/api/scan/health` | —                      | Liveness probe                          |

### Multipart form fields (`POST /api/scan`)

| Field                 | When required                | Description                                                              |
|------------------------|-------------------------------|----------------------------------------------------------------------------|
| `inputMode`            | always                        | `TERMS` or `CSV`                                                          |
| `termType`             | optional (default `NATURAL_LANGUAGE`) | `NATURAL_LANGUAGE` or `REGEX`                                    |
| `messageMode`          | always                        | `TEXT` or `FILE`                                                          |
| `terms`                | when `inputMode=TERMS`        | newline-separated term descriptions                                      |
| `csvFile`              | when `inputMode=CSV`          | CSV file — **2 columns**: `Term ID, Term Description`; a legacy 3rd column is tolerated and ignored |
| `messageText`          | when `messageMode=TEXT`       | raw message text                                                          |
| `messageFiles`         | when `messageMode=FILE`       | one or more message files — **each becomes its own scanned message**, all checked against the same term/disclaimer set |
| `disclaimerInputMode`  | optional (default `NONE`)     | `NONE`, `TEXT`, or `CSV`                                                  |
| `disclaimers`          | when `disclaimerInputMode=TEXT` | disclaimer texts, separated by a **blank line** (not a single newline — one disclaimer may itself span multiple lines) |
| `disclaimerCsvFile`    | when `disclaimerInputMode=CSV`  | CSV file, same 2-column shape as a term CSV                            |
| `ruleName`             | optional                      | session name (default: `scanner-test-rule`)                              |

`termType=REGEX` terms are validated directly against Hyperscan — the
Lexicon Compile Service is never called for them.

### JSON request body (`POST /api/scan/json`)

Single-message, text-only (no file upload, no disclaimer CSV — those
require multipart):

```json
{
  "inputMode":   "TERMS",
  "termType":    "NATURAL_LANGUAGE",
  "messageMode": "TEXT",
  "terms":       "insider AND trading\npump OR dump",
  "messageText": "The insider passed information to his broker.",
  "ruleName":    "my-test-session"
}
```

### Response (`ScanResponse` — see `Models.java` for the authoritative shape)

```json
{
  "scanId": "3f8a1c2e-...",
  "scanDurationMs": 42,
  "termType": "Natural Language",
  "totalMessages": 1,
  "totalTerms": 2,
  "totalDisclaimers": 0,
  "matchedTerms": 1,
  "failedTerms": 0,
  "hasMatches": true,
  "messageResults": [
    {
      "messageId": "Pasted Message",
      "plainMessage": "The insider passed information to his broker.",
      "htmlHighlightedMessage": "The <mark class=\"lex-match\">insider</mark> passed...",
      "disclaimerSpans": [],
      "termResults": [
        {
          "termId": "my-test-session::1",
          "termDescription": "insider AND trading",
          "translatedPattern": ["(?i)insider", "(?i)trading"],
          "compilationStatus": "PASS",
          "requiresExclusionCheck": false,
          "matches": [
            { "startCharIndex": 4, "endCharIndex": 11, "matchedText": "insider", "inDisclaimer": false }
          ],
          "hasMatches": true,
          "hasDisclaimerOnlyMatches": false,
          "excludedByAndNot": false,
          "compilationError": null,
          "exclusionNote": null
        }
      ],
      "matchedTerms": 1,
      "hasMatches": true
    }
  ],
  "warnings": [],
  "status": "SUCCESS",
  "errorMessage": null
}
```

`translatedPattern` and (when `requiresExclusionCheck` is true) the
underlying exclusion pattern are always **lists**, not single strings —
one entry for a simple term, several when the Compile Service decomposed
a NEAR/FOLLOWEDBY structure into independent leaf patterns. A `hasMatches`
of `false` on a term can mean no match at all, every match falling inside
a disclaimer (`hasDisclaimerOnlyMatches=true`), or the AND NOT exclusion
condition being satisfied (`excludedByAndNot=true`, with `exclusionNote`
explaining why) — check which, don't assume "no alert" means "no match."

---

## GZIP support

| Direction | Mechanism                                        |
|-----------|---------------------------------------------------|
| Request   | `GzipRequestFilter` — decompresses gzip bodies    |
| Response  | Tomcat `server.compression.*` in `application.yml`|

Send a gzip-compressed JSON request:
```bash
echo '{"inputMode":"TERMS","messageMode":"TEXT","terms":"insider","messageText":"insider info"}' \
  | gzip | curl -s -X POST http://localhost:8090/api/scan/json \
    -H "Content-Type: application/json" \
    -H "Content-Encoding: gzip" \
    --data-binary @-
```

---

## Local development

```bash
# Prerequisites: Java 21, Maven 3.9+

# 1 — Build and run tests
mvn clean verify

# 2 — Start the service
mvn spring-boot:run

# 3 — Open the UI
open http://localhost:8090

# 4 — Point to a running Lexicon Compile Service
# Edit application.yml or set:
export LEXICON_COMPILE_SERVICE_BASE_URL=http://localhost:8080
mvn spring-boot:run
```

This project's request/response wire models are verified current against
the Compile Service's actual contract as of 2026-08-31 (see `CLAUDE.md`
"Current state") — a live Compile Service instance is still required for
Natural Language scans to work, since only it performs the operator-
language → pattern translation.

---

## Docker

```bash
# Build JAR
mvn package -DskipTests

# Build image
docker build -t lexicon-scanner-service:latest .

# Run (with Lexicon Compile Service in same Docker network)
docker run -p 8090:8090 \
  -e LEXICON_COMPILE_SERVICE_BASE_URL=http://lexicon-compile-service:8080 \
  lexicon-scanner-service:latest
```

### docker-compose example

```yaml
services:
  lexicon-compile-service:
    image: lexicon-compile-service:latest
    ports: ["8080:8080"]

  lexicon-scanner-service:
    image: lexicon-scanner-service:latest
    ports: ["8090:8090"]
    environment:
      SPRING_PROFILES_ACTIVE: docker
    depends_on: [lexicon-compile-service]
```

---

## GKE / Compute Engine deployment

1. Push the image to Artifact Registry:
   ```bash
   docker tag lexicon-scanner-service:latest \
     europe-west1-docker.pkg.dev/PROJECT/REPO/lexicon-scanner-service:latest
   docker push europe-west1-docker.pkg.dev/PROJECT/REPO/lexicon-scanner-service:latest
   ```

2. Deploy to GKE:
   ```bash
   kubectl set image deployment/lexicon-scanner-service \
     lexicon-scanner-service=europe-west1-docker.pkg.dev/PROJECT/REPO/lexicon-scanner-service:latest
   ```

3. Set `LEXICON_COMPILE_SERVICE_BASE_URL` as a Kubernetes environment
   variable or Secret.

4. Configure `SPRING_PROFILES_ACTIVE=cloud-run` (the Dockerfile default),
   which enables:
   - 10 s connect timeout / 60 s read timeout
   - `fallback-to-java-regex=false` (fail fast in production)

---

## Code coverage (JaCoCo)

```bash
mvn verify
open target/site/jacoco/index.html
```

Minimum thresholds enforced at `mvn verify`:
- **Line coverage**: 70 %
- **Branch coverage**: 60 %

---

## Key technical decisions

| Concern | Decision |
|---|---|
| Natural Language scanning engine | Plain `java.util.regex`, not Hyperscan — every PASS term's `resolvedPatterns` is parsed into a `ResolvedPatternTree` and evaluated by `ResolvedPatternMatcher` directly against the stripped message text. No Hyperscan database is built for Natural Language terms at all |
| AND NOT | Required/excluded sides of a `ResolvedPatternTree.AndNot` evaluated independently in Java — no native Hyperscan `HS_FLAG_COMBINATION` anywhere in this project any more (confirmed unreliable by Hyperscan's own docs; fixed the same way the Compile Service fixed its own equivalent code) |
| Term decomposition (NEAR/FOLLOWEDBY) | Real word-distance/order verification via `ResolvedPatternMatcher`'s backtracking algorithm (ported from `lexicon-scan-engine`'s `ResolvedPatternAreaEvaluator`) — not plain boolean AND over leaf presence |
| Hyperscan match start positions (Regex terms only) | `ExpressionFlag.SOM_LEFTMOST` — Regex terms always compile as one plain expression per term, never `QUIET`/`COMBINATION` |
| Test-environment Hyperscan failure (Regex path only) | `fallback-to-java-regex=true` default; same `RawMatch` semantics as the native path |
| Multi-byte UTF-8 offset mapping | byte-offset ↔ char-index mapping in `HyperscanScanService`/`Models.StrippedMessage` (Regex path); `ResolvedPatternMatcher` operates directly in Java char-index space, no byte-offset conversion needed |
| Disclaimer suppression | Exact-substring match on HTML-stripped text, not a Hyperscan feature — deliberately simpler than the Scan Engine's approach; see `CLAUDE.md` |
| Micro-frontend UI isolation | All CSS/JS scoped inside `.la` / `.lex-*` prefix; no external CDN deps |
| Docker base image | `eclipse-temurin:21-jre-jammy` (glibc, not Alpine) — Hyperscan `.so` requires glibc |

---

## Where to look next

- **`CLAUDE.md`** — the authoritative, most-recently-verified account of
  the Compile Service contract and this project's own known gaps. Read it
  before touching `Models.java`, `LexiconCompileClient.java`,
  `LexiconScanOrchestrator.java`, or `HyperscanScanService.java`.

# What each folder does

The module follows the Pyramid layers. Requests come in at the top, and the database and model sit at the bottom:

```
app  →  ws-rest / ws-grpc  →  service-api  →  service-impl  →  dal-api  →  dal-jpa / dal-naru
```

## Java modules (Maven)

| Folder | Layer | What it does |
|---|---|---|
| `app` | Startup | Starts Spring Boot and connects the pieces (`Application.java`). Also holds the command-line tools: `profile` import/export (`Maintenance`) and `projection-replay`. |
| `ws-rest` | Web (HTTP) | All the HTTP routes on port 8082 (`LearningController`), plus the bearer-token check (`ServiceAuth`). |
| `ws-grpc` | Web (gRPC) | The gRPC server on port 9092 that sends Ditto the counts (teaching chats, practice chats, facts, links). The `.proto` file is here too. |
| `service-api` | Service contract | The `Learning` interface. It's the only thing the controllers are allowed to call. |
| `service-impl` | Business logic | The real work: running turns (`SessionService`), tools per role (`LessonTools`), side chats, the knowledge graph, settings, jobs, and old-data import. The tutor's prompts are in `src/main/resources/prompts/`. |
| `dal-api` | Data contract | Interfaces the services need from below: `ProfileStore` (database), `LessonRuntime` (AI call), `KeyStore` (API keys). |
| `dal-jpa` | Database | `ProfileStore` built on JPA + H2 (file `learning.mv.db`). It has one table, `learning_records`, which holds JSON documents. |
| `dal-naru` | AI | `LessonRuntime` built on NARU (`NaruRuntime`). It sends the chat to the model server (Ollama/Qwen, OpenAI, OpenRouter or the fixture) and checks the tool calls. Includes the circuit breaker (`ModelTransport`). |
| `infra` | Shared helpers | JSON helpers, error types (`Refusal`) and the projection record. |
| `snapshot-api` | NARU library | NARU's public API, an unmodified copy of upstream NARU. |
| `snapshot-engine` | NARU library | NARU's engine, an unmodified copy of upstream NARU. |
| `contract-tests` | Tests | Tests that span modules. `PyramidTest` (ArchUnit) fails the build if a layer rule is broken. The others test turns, graph and side chats, restart recovery, and old-data migration. |

## Other folders

| Folder | What it does |
|---|---|
| `test-console` | The browser frontend at http://127.0.0.1:8090 (`index.html` for the demo, `advanced.html` for every route). `server.py` starts and stops Ollama/Qwen, the engine and the fixtures, and adds the access token to requests. |
| `fixtures` | Test inputs. `model.py` is a fake, scripted model server. `demo.py` runs the demo end to end. `verify_http.py` checks the HTTP contract. `projection.jsonl` holds sample gRPC revisions. `golden-data/` holds a sample LearnHarness profile for migration tests. |
| `helpers` | Small scripts. `read-profile.py` reads sessions and the graph from a running engine. `learning-scripts/` reads an exported profile. `desktop-ipc.cjs` is the Electron desktop bridge. |

## Files at the root

| File | What it is |
|---|---|
| `pom.xml` | The parent Maven build that lists all the modules |
| `README.md` | Build and run instructions |
| `DEMO.md` | Presentation script |
| `CODEBASE-WALKTHROUGH.md` | Longer tour of the code |
| `COMPATIBILITY.md` | What is still missing or partly done compared with LearnHarness |

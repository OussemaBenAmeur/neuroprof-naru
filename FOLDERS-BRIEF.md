# naruprof-naru: what each folder does

Requests enter at the top and go down one layer at a time:

```
app → ws-rest / ws-grpc → service-api → service-impl → dal-api → dal-jpa / dal-naru
```

## Java modules

| Folder | What it does |
|---|---|
| `app` | Starts the application and connects all the pieces. Also has command-line tools (profile import/export, projection replay) and the adapters for PDF notes and voice dictation. |
| `ws-rest` | The HTTP API on port 8082 that Vert.x calls. Rejects requests without the right token. |
| `ws-grpc` | The gRPC server on port 9092 that Ditto calls to get the learner's summary counts. The `.proto` file is here. |
| `service-api` | The one interface (`Learning`) the API layers may call. |
| `service-impl` | The business logic: runs lessons, tools per role, side chats, the knowledge graph, settings, jobs. The tutor's prompts are in `src/main/resources/prompts/`. |
| `dal-api` | Interfaces the logic needs from below: storage, the AI call, API keys, PDF reading, dictation. |
| `dal-jpa` | Storage. Saves everything with JPA in an H2 database, as JSON records in one table. |
| `dal-naru` | The AI call. Runs the tutor through NARU and talks to the model server. Includes the circuit breaker. |
| `infra` | Small shared helpers: JSON, error types. |
| `snapshot-api`, `snapshot-engine` | An unmodified copy of the NARU library. |
| `contract-tests` | Tests across modules. `PyramidTest` fails the build if a layer rule is broken. |

## Other folders

| Folder | What it does |
|---|---|
| `test-console` | A browser page (http://127.0.0.1:8090) to try the module by hand. |
| `fixtures` | Test inputs: a fake model server, demo scripts, sample data. |
| `helpers` | Small scripts to read a profile and the desktop bridge. |

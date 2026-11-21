# NaruProf NARU

Private learning engine for the Vert.x HTTP relay and the Ditto projection consumer. The Spring Boot assembly uses the supplied NARU snapshot, a Nuts workspace rooted under the profile directory, and file-backed H2 through JPA. The HTTP and gRPC listeners have separate service credentials. [Integration contract](../implementation-prompts/contracts/integration-v1.md) · [Compatibility matrix](COMPATIBILITY.md) · [Presentation guide](DEMO.md)

| Component | Version |
| --- | --- |
| NaruProf NARU | 1.0.0 |
| Java | 25 |
| Spring Boot | 4.0.9 |
| H2 | 2.4.240, managed by Spring Boot |
| Hibernate | 7.2.25.Final, managed by Spring Boot |
| NARU snapshot | `68412108a0526e84785994972a694e861f2ac321` |
| Nuts API / embedded runtime | 1.1.0 / 1.1.0.0 |
| gRPC Java / protoc | 1.75.0 / 3.25.8 |
| LearnHarness baseline | `cb25c8e49b85eed9e78315584731cad73a0118a6` |

## Build and launch

Use JDK 25 and Maven 3.9+ with access to Maven Central. Python 3 runs the scripted provider. Poppler supplies `pdfinfo` and `pdftotext` for PDF imports. Node.js runs the desktop helper fixture.

Run from this directory. Choose the classifier for the build host where it differs from the Linux x86-64 default.

```sh
mvn verify
mvn -Preal-deps verify
# macOS on Apple silicon
mvn -Dprotoc.classifier=osx-aarch_64 verify
```

```powershell
# Windows on x86-64
mvn -Dprotoc.classifier=windows-x86_64 verify
```

For a standalone smoke test, run `python3 fixtures/model.py` in one terminal and the following in another. The tokens are local fixture values.

```sh
export NARUPROF_DATA_DIR="$(mktemp -d)"
export NARUPROF_PROFILE_ID=f7933216-20db-49cb-90e0-3a9d5396966d
export NARUPROF_MODEL_URL=http://127.0.0.1:18082/v1/chat/completions
export NARUPROF_MODEL_ID=fixture
export VERTX_TO_NARU_TOKEN=fixture-vertx
export TWIN_TO_NARU_TOKEN=fixture-twin
java -jar app/target/app-1.0.0.jar
```

On PowerShell set the same variables via `$env:VARIABLE = 'value'` and use a new writable directory for `NARUPROF_DATA_DIR`. Run `python fixtures/model.py` and the same JAR command.

In integrated mode the launcher sets `NARU_HTTP_URL` (default `http://127.0.0.1:8082`), `NARU_GRPC_TARGET` (default `127.0.0.1:9092`), the profile identity, and the two service tokens. It uses `GET /internal/health/live` and `GET /internal/health/ready` on the private HTTP listener with the Vert.x token. OpenAI and OpenRouter Chat Completions adapters resolve keys at request time. `LH_AUTH_FILE` defaults to `auth.json` under the profile directory, with environment precedence. Set `NARUPROF_MODEL_URL` and `NARUPROF_MODEL_ID` for the explicit `learning` fixture adapter. `NARUPROF_MODEL_TOKEN` is its optional bearer credential.

## Peer fixtures

- Run `python3 fixtures/verify_http.py` with the configured HTTP URL and relay credential. The scripted probe first produces `call_probe`, then consumes the learner's tool result and produces a plan followed by a node-tagged quiz. The saved result contains the original `toolCallId`, an answer with `details.correct=true`, and the subsequent native assistant and tool results.
- [The projection JSONL fixture](fixtures/projection.jsonl) shows revisions 0 through 4 in protobuf JSON. To produce revisions 1 through 3 on a fresh profile, create a teaching chat and a practice chat, then submit a multipart file named `fractions.md` to `POST /api/graph/import` with the fixture provider. Its scripted extraction supplies two distinct propositions and one argument. The supplied [proto](ws-grpc/src/main/proto/learning_projection_v1.proto) defines the RPCs and field numbers. Run `java -jar app/target/app-1.0.0.jar projection-replay fixtures/projection.jsonl` as a standalone fixture for Ditto. It advances one revision every two seconds and uses `NARU_GRPC_TARGET` and `TWIN_TO_NARU_TOKEN`. `Watch(after_sequence=1)` yields revisions 2 and 3 in order.
- Run `node --test fixtures/desktop-test.cjs` for an inherited Node IPC exchange. The executable [desktop peer](fixtures/desktop-peer.cjs) exercises the ChatGPT and subscription status packets used by Electron. The Java provider layer does not consume these leases yet.

## Profile migration

Run maintenance commands while the application is stopped. Start with an empty destination profile and retain the original LearnHarness directory and auth file.

```sh
java -jar app/target/app-1.0.0.jar profile preview /path/to/legacy
java -jar app/target/app-1.0.0.jar profile import /path/to/legacy PREVIEW_SHA256
java -jar app/target/app-1.0.0.jar profile export /path/to/new-export
python3 helpers/learning-scripts/sessions.py --data-dir /path/to/new-export --format json list
python3 helpers/learning-scripts/graph.py --data-dir /path/to/new-export --format json propositions
```

Preview reports file digests and the overall SHA-256. Import stages files by digest, then commits the canonical data, receipt and projection together. Repeat the import with the same SHA after interruption. Export creates a new directory with legacy-compatible files. For a running server, use `python3 helpers/read-profile.py sessions --format json list`. It fetches a consistent snapshot through the private relay credential and runs the shipped learning script on a temporary export. Roll back by restarting LearnHarness against the untouched original. Credentials stay outside the import and export. Restore the old auth file through `LH_AUTH_FILE` or set keys in the private Settings flow.

`profile status` prints the installed profile identity and the current projection snapshot. The maintenance command `profile retire PROFILE_ID` publishes a durable tombstone and prevents further writes to that profile. Run it only against the dedicated test profile when exercising the deletion fixture.

## Verification

The current workspace passed the NARU task and provider event parser tests, a two-client DispatcherServlet probe/answer test with an in-memory transaction fixture, graph import and deletion, side-chat redaction and idempotency, the ArchUnit rules, and the desktop IPC fixture. The gRPC test passed in-process against the locally installed gRPC 1.84 and protobuf 4.36.1 libraries. The earlier file-backed H2 fixture run also passed the probe/restart and golden import/export checks.

The pinned full Maven reactor and the `real-deps` socket integration test remain unverified here. This environment cannot resolve Maven Central, lacks part of the dependency cache and denies TCP loopback binding. The [compatibility matrix](COMPATIBILITY.md) lists behavior still missing or partially implemented.

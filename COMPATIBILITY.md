# Compatibility matrix

## HTTP routes

| Method | Route | Evidence or remaining gap |
| --- | --- | --- |
| POST | `/api/activity/abort` | Partial. Turn and extraction stop. Plugin and worker stop remain open. |
| GET | `/api/activity` | Partial. Extraction and stored running jobs. Plugin worker state and pricing remain open. |
| POST | `/api/chat` | Probe answer, stale occurrence and overlap exercised in [ServletProbeTest](contract-tests/src/test/java/org/naruprof/proof/ServletProbeTest.java). Plugin submissions, image-bearing answers and subscription staging remain open. |
| POST | `/api/chats/[id]/artifact-ui` | Gap. Plugin artifact UI calls. |
| POST | `/api/chats/[id]/dismiss` | Partial. Native question and practice dismissal, including parking. Plugin occurrence handling and exact deferred conclude pair remain open. |
| GET | `/api/chats/[id]/images/[snapshotId]` | Partial. Owned snapshot retrieval and imported images. Image submission and normalization remain open. |
| GET | `/api/chats/[id]/markdown` | Partial. Markdown attachment from visible native history. Full original markdown projection remains open. |
| GET | `/api/chats/[id]` | Probe reopen exercised in [ServletProbeTest](contract-tests/src/test/java/org/naruprof/proof/ServletProbeTest.java). Full view pricing and compaction state remain open. |
| PUT | `/api/chats/[id]` | Partial. Durable title rename. Original metadata compatibility test pending. |
| DELETE | `/api/chats/[id]` | Partial. Canonical deletion with provenance policy. Exact legacy deletion statistics remain open. |
| PUT | `/api/chats/[id]/settings` | Partial. Model selection persisted. Exact catalogue validation and thinking-level clamping remain open. |
| PATCH | `/api/chats/[id]/side-chats/[threadId]` | Implemented open/closed persistence. Dedicated route test pending. |
| GET | `/api/chats/[id]/side-chats/[threadId]` | Partial. NDJSON snapshot and tail. Original partial delivery edge cases remain open. |
| POST | `/api/chats/[id]/side-chats/[threadId]/stop` | Partial. Explicit stop and partial reply persistence. Dedicated route test pending. |
| GET | `/api/chats/[id]/side-chats` | Thread listing exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). |
| POST | `/api/chats/[id]/side-chats` | Tool-less execution, spoiler redaction and request deduplication exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). Exact citation authority and transcript compaction integration remain open. |
| POST | `/api/chats/[id]/steer` | Partial. Durable text steer queue keyed by client ID. Images and exact stream emission remain open. |
| GET | `/api/chats/[id]/stream` | Settled sentinel, abort and detach exercised in [RecoveryTest](contract-tests/src/test/java/org/naruprof/proof/RecoveryTest.java). Full original event variants remain open. |
| GET | `/api/chats` | Partial. Session list, derived phase and activity flags. Browser contract test pending. |
| POST | `/api/chats` | Probe session creation exercised in [ServletProbeTest](contract-tests/src/test/java/org/naruprof/proof/ServletProbeTest.java). Projection counter trace covered by [RecoveryTest](contract-tests/src/test/java/org/naruprof/proof/RecoveryTest.java). |
| GET | `/api/claude-code` | Gap. Native Claude Code discovery and execution. |
| GET | `/api/custom-provider` | Gap. Custom provider discovery, binding, model keys and restart. |
| POST | `/api/custom-provider` | Gap. Custom provider discovery, binding, model keys and restart. |
| PATCH | `/api/custom-provider` | Gap. Custom provider discovery, binding, model keys and restart. |
| DELETE | `/api/custom-provider` | Gap. Custom provider discovery, binding, model keys and restart. |
| GET | `/api/events` | Partial. Named events and 25-second heartbeat. Full plugin and worker event set remains open. |
| GET | `/api/graph/costs` | Gap. Extraction billing aggregation. |
| POST | `/api/graph/coverage/extract` | Partial. Chat prose extraction, cursor coverage and cancellation. Exact original candidate rules and coverage reporting remain open. |
| POST | `/api/graph/import` | Canonical merge, repeat import and provenance exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). Server multipart and Poppler conversion require live verification. |
| DELETE | `/api/graph/nodes/[id]` | Argument deletion and projection exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). |
| PUT | `/api/graph/positions` | Partial. Persist finite coordinates. Original layout normalization remains open. |
| GET | `/api/graph/render-settings` | Implemented saved rendering settings and empty-profile null. Dedicated route test pending. |
| PUT | `/api/graph/render-settings` | Implemented rendering-setting normalization and persistence. Dedicated route test pending. |
| GET | `/api/graph` | Canonical and derived graph exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). Original cluster and layout generation remain open. |
| GET | `/api/graph/sources` | Source organization and import folder discovery exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). |
| DELETE | `/api/graph/sources` | Shared provenance and empty-folder deletion exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). |
| POST | `/api/graph/sources` | Implemented source/folder rename, move and folder create/delete. Dedicated route test pending. |
| GET | `/api/graph/status` | Partial. Extraction phases and chunk index. Per-job usage and original concurrent chunk reporting remain open. |
| GET | `/api/jobs` | Partial. Canonical job record read and turn liveness. Worker execution remains open. |
| PUT | `/api/keys/[id]` | Protected key write and environment precedence. Exact provider-specific validation test pending. |
| DELETE | `/api/keys/[id]` | Protected key deletion and retained environment override. Dedicated route test pending. |
| GET | `/api/keys` | Masked status with environment precedence. Dedicated route test pending. |
| GET | `/api/models` | Partial. Saved selection catalogue and fixture/OpenAI/OpenRouter availability. Complete Pi catalogue and thinking capabilities remain open. |
| GET | `/api/plugins/[id]/assets/[...file]` | Gap. Allowlisted binary plugin assets. |
| POST | `/api/plugins/[id]/call` | Gap. Trusted CommonJS plugin calls. |
| POST | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| OPTIONS | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| GET | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| HEAD | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| PUT | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| PATCH | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| DELETE | `/api/plugins/[id]/documents/markdown` | Gap. Plugin document conversion and explicit HTTP method handling. |
| PUT | `/api/plugins/[id]` | Gap. Plugin lifecycle. |
| GET | `/api/plugins` | Gap. Plugin inventory, installation and enablement. |
| POST | `/api/plugins` | Gap. Plugin inventory, installation and enablement. |
| GET | `/api/search` | Gap. Search provider status and drivers. |
| PUT | `/api/settings/[section]` | Partial. Durable section writes and model slots. Full schema validation remains open. |
| PUT | `/api/settings/prompts/[...segment]` | Implemented recognized prompt segment override. Dedicated route test pending. |
| DELETE | `/api/settings/prompts/[...segment]` | Implemented durable override removal. Dedicated route test pending. |
| GET | `/api/settings/prompts` | Implemented prompt segments and overrides. Dedicated route test pending. |
| GET | `/api/settings` | Settings and migration evidence in [GoldenMigrationTest](contract-tests/src/test/java/org/naruprof/proof/GoldenMigrationTest.java). Full settings defaults and resolution remain open. |
| GET | `/api/stt/token` | Implemented Deepgram grant driver. Live verification requires a Deepgram key. |
| GET | `/api/subscriptions/browser` | Gap. Browser-mediated subscription login. |
| POST | `/api/subscriptions/browser` | Gap. Browser-mediated subscription login. |
| GET | `/api/subscriptions` | Gap. Subscription connections and consent. |
| POST | `/api/subscriptions` | Gap. Subscription connections and consent. |
| DELETE | `/api/themes/[id]` | Partial. Stored theme deletion. Original fallback test pending. |
| GET | `/api/themes` | Partial. Catalogue and persisted themes. Original validation test pending. |
| POST | `/api/themes` | Partial. Theme validation and persistence. Original form compatibility test pending. |

## Provider and plugin capabilities

| Capability | Evidence or remaining gap |
| --- | --- |
| Scripted Chat Completions fixture | [NaruRuntimeTest](dal-naru/src/test/java/org/naruprof/runtime/NaruRuntimeTest.java) exercises a real NARU task and stream parser. [The HTTP fixture](fixtures/model.py) is available for live relay integration. |
| OpenAI API key | Chat Completions adapter uses protected key resolution. Live provider verification, exact cache policy and known pricing remain open. |
| OpenRouter API key | Chat Completions adapter uses protected key resolution. Live provider verification, model capability discovery and known pricing remain open. |
| Other Pi providers and custom endpoint | Gap. The launcher-configured fixture endpoint covers one explicit OpenAI-compatible adapter. Original custom endpoint lifecycle remains open. |
| Claude Code, ChatGPT and subscription adapters | Gap. [Desktop IPC fixture](fixtures/desktop-test.cjs) exercises status and request/reply contracts. Native lease consent and provider execution remain open. |
| Deepgram | Token grant client requests 300 seconds. Live integration needs a grant-capable key. |
| Search, local attachments and vision | Gap. Configured search adapters, file access grants, original image pipeline, PDF/SVG rendering and model image payloads remain open. Notes PDF extraction has a separate Poppler driver. |
| Research, demo, PDF, visual and plugin workers | Gap. Worker execution and delivery recovery need separate implementation. Canonical job record reads and foreground delivery of recorded results are exercised in [GraphAndSideTest](contract-tests/src/test/java/org/naruprof/proof/GraphAndSideTest.java). |
| Flashcards | Gap. Trusted CommonJS host execution, management and grading routes. |
| Coding questions | Gap. Saved-schema grading and practice problem payload compatibility. |
| Drawing pad | Gap. Image submission and annotation behavior. |
| Rich code blocks | Gap. Renderer asset and backend extension lifecycle. |

## Local persistence and projection

- [GoldenMigrationTest](contract-tests/src/test/java/org/naruprof/proof/GoldenMigrationTest.java) reads the supplied golden profile, verifies counts, exports and reopens it using the original learning scripts. A private live snapshot route supports current reads with the bundled scripts. Complete plugin backend state recovery remains open.
- [RecoveryTest](contract-tests/src/test/java/org/naruprof/proof/RecoveryTest.java) covers no inference after a refused write, lease recovery, abort, disconnect, failed settlement and the projection trace using an H2 test fixture.
- [ProjectionRpcTest](ws-grpc/src/test/java/org/naruprof/grpc/ProjectionRpcTest.java) covers bearer authorization, unknown profiles, uint64 cursor refusal, replay and live tombstones. The default Maven build pins the production binding versions.
- [LearningApplicationIT](app/src/test/java/org/naruprof/app/LearningApplicationIT.java) drives the actual Spring Boot listeners, H2 JPA, NARU, scripted HTTP provider and a gRPC client. Execution requires the `real-deps` build profile and TCP loopback.

## Remaining workflow parity

The module implements the core probe-to-plan interaction and basic practice flow. Full compaction, scoped drafts, plugin occurrence authority, image handling, graph cost accounting, worker orchestration and background consent are pending. The notes pipeline uses bounded chunks, model audit and transactional merge. Exact LearnHarness extractor prompts, coverage fingerprints and attribution rules remain pending.

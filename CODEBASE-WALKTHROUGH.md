# NaruProf NARU code walkthrough

## 1. Architecture and repository layout

A teaching request starts at the NaruProf HTTP API. In the integrated architecture, Vert.x relays it to the private Spring Boot listener. The learning service saves the input and schedules a turn. A NARU task calls the configured model endpoint, then the learning service applies the reply and makes it available over HTTP.

| Component | Input and responsibility | Handoff |
| --- | --- | --- |
| **NaruProf application** | Takes a chat ID and learner input. Owns admission, role selection, prompts, teaching rules, chat records, and the client API. | Gives NARU the selected model, prompt, history, and allowed tools. Takes back the assistant message, applies it, and publishes the result. |
| **NARU runtime** | Runs a task with a directive, model protocol, and tool registry supplied by the application. | Calls the configured model protocol. The directive dispatches returned tool calls through the registry and gives the application an assistant message. |
| **Model endpoint** | Takes an OpenAI-compatible Chat Completions request containing messages and tool definitions. | Returns assistant text, tool calls, or both. The Python fixture returns scripted responses. |

At the workspace root, `naruprof-naru/` is the Java application. `learnharness/` contains the original project and its client contracts. `upstream/naru/` contains the upstream source reference. `pyramid/` contains architecture guidance and example projects. `implementation-prompts/` holds the implementation brief and integration contracts.

Inside [the Maven reactor](pom.xml), Java code lives under each module’s `src/main/java`, with tests in `src/test/java`.

| Folder | What it contains |
| --- | --- |
| [app](app/) | Spring Boot entry point and wiring, plus maintenance commands and external integrations. |
| [ws-rest](ws-rest/) | Spring MVC routes, private bearer authentication, and HTTP stream framing. |
| [service-api](service-api/) | `Learning` for the application API and `ProjectionRead` for summary reads. |
| [service-impl](service-impl/) | Turn orchestration, transcripts, tool effects, settings, and the prompt resources. Also owns side conversations and graph operations. |
| [dal-api](dal-api/) | Interfaces the services use for inference, profile storage, credentials, PDF conversion, and dictation. |
| [dal-naru](dal-naru/) | NaruProf’s adapter from the inference interface to a NARU task, plus model HTTP transport and message conversion. |
| [snapshot-api](snapshot-api/) and [snapshot-engine](snapshot-engine/) | Bundled NARU interfaces and engine implementation used by the adapter. |
| [dal-jpa](dal-jpa/) | Transactional profile storage through JPA and file-backed H2. |
| [ws-grpc](ws-grpc/) | The private RPC server and Protobuf contract for profile summaries. |
| [infra](infra/) | Shared JSON helpers, error responses, upload data, and the projection record. |
| [contract-tests](contract-tests/) | Tests that cross application boundaries, including servlet admission and chat reopening. |
| [fixtures](fixtures/) and [helpers](helpers/) | Scripted model and sample data, plus desktop and profile utilities. |

## 2. First turn — from HTTP to a pending probe

### Enter through Spring MVC

1. **`Application.main` and its bean methods** wire `JpaProfile` and `NaruRuntime` into `LearningModule`, then expose it through `LearningController`. The default HTTP address is `127.0.0.1:8082`. `NARUPROF_MODEL_URL` and `NARUPROF_MODEL_ID` configure the explicit model endpoint and ID used for the fixture path. [Application.java](app/src/main/java/org/naruprof/app/Application.java)
2. **`ServiceAuth.doFilterInternal`** requires the service bearer credential from `VERTX_TO_NARU_TOKEN`. **`LearningController.create`** handles `POST /api/chats`. A body such as `{"title":"Fractions"}` creates a teaching chat and returns its ID. **`LearningController.submit`** handles the separate `POST /api/chat`. It requires a nonblank `chatId` and an `input` object and delegates through **`LearningModule.submit`**. [ServiceAuth.java](ws-rest/src/main/java/org/naruprof/http/ServiceAuth.java) · [LearningController.java](ws-rest/src/main/java/org/naruprof/http/LearningController.java) · [LearningModule.java](service-impl/src/main/java/org/naruprof/learning/LearningModule.java)

```json
{
  "chatId": "<returned chat ID>",
  "input": {"type": "text", "text": "Help me understand fractions"}
}
```

### Admit the turn and hand it to NARU

3. **`SessionService.submit`** checks for a live turn and an existing lease. An overlapping active turn gets `409`. Inside a profile write transaction, it loads the chat, calls **`Transcript.accept`**, checks model configuration, and saves the updated chat and a lease identifying the turn owner. The text branch of `accept` appends a user message. After the transaction commits, the service registers the live stream and schedules **`execute`**. The POST returns `200 {"ok":true}` for this path. [SessionService.java](service-impl/src/main/java/org/naruprof/learning/SessionService.java) · [Transcript.java](service-impl/src/main/java/org/naruprof/learning/Transcript.java)
4. **`SessionService.request`** turns that accepted state into a **`LessonRuntime.Request`**. **`LessonTools.role`** selects `prober` for a fresh teaching chat, so `probe` is among the available tools. **`SettingsService.prompt`** assembles the role’s prompt segments. **`ModelSelection.resolve`** selects the provider and model ID. The request contains those values and the chat record. [LessonRuntime.java](dal-api/src/main/java/org/naruprof/ports/LessonRuntime.java) · [LessonTools.java](service-impl/src/main/java/org/naruprof/learning/LessonTools.java) · [SettingsService.java](service-impl/src/main/java/org/naruprof/learning/SettingsService.java) · [ModelSelection.java](service-impl/src/main/java/org/naruprof/learning/ModelSelection.java)
5. **`NaruRuntime.respond`** opens a NARU session and runs a task with the `/learning-turn` directive. The directive converts the prompt and record via **`NativeHistory.modelMessages`**, then calls **`Provider.chat`**. The provider serializes messages and **`ToolSchemas`** as Chat Completions JSON. **`ModelTransport.http`** posts it to the model endpoint and assembles the streamed response. [NaruRuntime.java](dal-naru/src/main/java/org/naruprof/runtime/NaruRuntime.java) · [NativeHistory.java](dal-naru/src/main/java/org/naruprof/runtime/NativeHistory.java) · [Provider.java](dal-naru/src/main/java/org/naruprof/runtime/Provider.java) · [ToolSchemas.java](dal-naru/src/main/java/org/naruprof/runtime/ToolSchemas.java) · [ModelTransport.java](dal-naru/src/main/java/org/naruprof/runtime/ModelTransport.java)

To step into the bundled NARU code, open **`NaruSessionImpl.newTask`** at the task creation call and **`NaruRegistryImpl.dispatch`** at the tool dispatch call. The learning directive and the tool implementation are both registered by `NaruRuntime`. [NaruSessionImpl.java](snapshot-engine/src/main/java/net/thevpc/naru/impl/engine/NaruSessionImpl.java#L323) · [NaruRegistryImpl.java](snapshot-engine/src/main/java/net/thevpc/naru/impl/registry/NaruRegistryImpl.java#L408)

### Save and deliver the question

6. **`model.py: reply`** sees an available `probe` tool and no previous probe in the supplied history. It returns `call_probe` with the question “Which fraction equals one half?” and options `2/4` and `1/4`. NARU dispatches the call to **`NaruRuntime.RoleTool.execute`**, which checks required fields. The directive returns an assistant message containing the `toolCall` block. [model.py](fixtures/model.py) · [NaruRuntime.java](dal-naru/src/main/java/org/naruprof/runtime/NaruRuntime.java)
7. **`SessionService.execute`** checks that the turn still owns its lease and stabilizes tool IDs. **`LessonTools.apply`** appends the assistant message and treats the probe as a question awaiting learner input. The service commits the chat, removes the lease, and emits `message_end`. **`TurnStream.consume`** delivers frames through **`LearningController.stream`** at `GET /api/chats/{id}/stream`. An attached client can see the probe in a `message_update` frame and the final message in `message_end`. The stream ends with `[DONE]`. A client attaching after settlement receives `turn-settled` and `[DONE]`, then can fetch the committed probe from `GET /api/chats/{id}`. [SessionService.java](service-impl/src/main/java/org/naruprof/learning/SessionService.java) · [LessonTools.java](service-impl/src/main/java/org/naruprof/learning/LessonTools.java) · [TurnStream.java](service-impl/src/main/java/org/naruprof/learning/TurnStream.java) · [LearningController.java](ws-rest/src/main/java/org/naruprof/http/LearningController.java)

## 3. Second turn — from the learner’s answer to a teaching check

The learner selects `2/4`. The fixture client represents that choice with `correct: true` and sends another `POST /api/chat`. Use the IDs and index from the saved assistant message. The single-block fixture probe is at content index `0`.

```json
{
  "chatId": "<same chat ID>",
  "input": {
    "type": "answer",
    "toolCallId": "call_probe",
    "answer": {"kind": "quiz", "correct": true},
    "occurrence": {
      "messageId": "<saved assistant message ID>",
      "contentIndex": 0,
      "toolCallId": "call_probe",
      "toolName": "probe"
    }
  }
}
```

1. **`Transcript` and `Transcript.accept`** reconstruct pending questions from saved assistant calls and remove them as matching `toolResult` messages appear. Admission requires the submitted call ID to match the first pending question. When provided, the occurrence must match the message ID, content index, call ID, and tool name or the service returns `400`. The accepted answer is appended as a `toolResult` with `toolCallId: "call_probe"`, `toolName: "probe"`, the full answer object in `details`, and compact answer JSON in its text content. This path stores the correctness value supplied by the client. [Transcript.java](service-impl/src/main/java/org/naruprof/learning/Transcript.java) · [demo.py](fixtures/demo.py)
2. **`ProfileStore`, `JpaProfile`, and `StoredRecord`** show where the state lives. The `chats/<id>` record is one JSON payload in the H2 `learning_records` table. It holds the learner message, assistant call, answer result, and eventually the plan tree. The separate `leases/<id>` record tracks in-flight ownership and partial output. The SSE frame buffer is in memory. `SessionService.get` reads the saved chat for the HTTP response, and the admission transaction reads that same record to validate the next input. [ProfileStore.java](dal-api/src/main/java/org/naruprof/ports/ProfileStore.java) · [JpaProfile.java](dal-jpa/src/main/java/org/naruprof/jpa/JpaProfile.java) · [StoredRecord.java](dal-jpa/src/main/java/org/naruprof/jpa/StoredRecord.java) · [SessionService.java](service-impl/src/main/java/org/naruprof/learning/SessionService.java)
3. **`NativeHistory.modelMessages` and `Provider.chat`** rebuild the model conversation from the saved record. The adapter indexes results by `toolCallId` and puts a tool message immediately after the assistant call it answers. The wire request carries `role: "tool"` and `tool_call_id: "call_probe"`. [NativeHistory.java](dal-naru/src/main/java/org/naruprof/runtime/NativeHistory.java) · [Provider.java](dal-naru/src/main/java/org/naruprof/runtime/Provider.java)
4. **`model.py: reply`** scripts the next calls using the offered tools and previous call names. Seeing the prior probe makes it return `probe_done`. **`LessonTools.apply`** checks that probing is resolved and assessment strands are present, then saves an acknowledgment. **`Transcript`** derives the `plan` phase from that successful result, so the next step uses the teacher role. The fixture returns `plan_init`. **`PlanTree.parse`** parses the supplied Mermaid into the chat’s `tree`. The fixture then returns `quiz` with `nodeId: "scale"`. The service checks that the node exists and settles with that quiz pending. These model steps run within the same admitted answer turn. [model.py](fixtures/model.py) · [LessonTools.java](service-impl/src/main/java/org/naruprof/learning/LessonTools.java) · [Transcript.java](service-impl/src/main/java/org/naruprof/learning/Transcript.java) · [PlanTree.java](service-impl/src/main/java/org/naruprof/learning/PlanTree.java)

The fixture supplies the question, assessment, plan text, and quiz. NaruProf implements the tool allowlist, durable call-to-answer linkage, phase transitions, plan validation, and publication. The fixture’s probe branch depends on tool names and history, so the same fresh-chat question is used for “Help me understand fractions” and the different input shown in [DEMO.md](DEMO.md). The guide was checked against source. [ServletProbeTest.scenario](contract-tests/src/test/java/org/naruprof/proof/ServletProbeTest.java) is a focused reading companion for admission, stale occurrences, the outbound tool result, and reopening the chat. It uses its own scripted response.

## 4. Follow the adjacent paths when they come up

- **Knowledge graph.** The `tree` above is the plan for one chat. The profile graph stores propositions, reasoning chains, and source provenance. For imported notes, follow `LearningController.importNotes` to `GraphExtraction.importNotes` and then `GraphService.merge`. `SourceDirectory` handles source and folder organization. `RenderSettings` validates graph display settings. [GraphExtraction.java](service-impl/src/main/java/org/naruprof/learning/GraphExtraction.java) · [GraphService.java](service-impl/src/main/java/org/naruprof/learning/GraphService.java) · [SourceDirectory.java](service-impl/src/main/java/org/naruprof/learning/SourceDirectory.java) · [RenderSettings.java](service-impl/src/main/java/org/naruprof/learning/RenderSettings.java)
- **Side chat.** For a hint anchored to a teaching message, follow `LearningController.askSide` to `SideChatService.ask`, `admit`, and `lesson`. It stores separate threads in the chat, filters pending answer keys from the helper context, and streams the reply as NDJSON. [SideChatService.java](service-impl/src/main/java/org/naruprof/learning/SideChatService.java)
- **gRPC.** Follow `ProfileLedger.project` to see where count changes create committed revisions, then `ProjectionServer.Feed.getSnapshot` and `watch` to see how Ditto reads the current summary or replays newer revisions. `ProjectionBinding` maps the Protobuf service to gRPC descriptors. Chat messages come from the HTTP chat endpoint. [ProfileLedger.java](dal-api/src/main/java/org/naruprof/ports/ProfileLedger.java) · [ProjectionServer.java](ws-grpc/src/main/java/org/naruprof/grpc/ProjectionServer.java) · [ProjectionBinding.java](ws-grpc/src/main/java/org/naruprof/grpc/ProjectionBinding.java) · [RPC schema](ws-grpc/src/main/proto/learning_projection_v1.proto)
- **Supporting services.** `Events` publishes named activity notifications. `JobService` recovers and delivers stored job updates. `UsageAccounting` associates assistant usage with turns. `KeyService` and `ProtectedKeys` handle credential status and storage. `ThemeService` manages themes. `LegacyProfile` and `Maintenance` implement profile migration and export. `PopplerNotesPdf` converts PDFs, `DeepgramDictation` retrieves dictation tokens, and `ProjectionReplay` serves recorded projection data. [Learning services](service-impl/src/main/java/org/naruprof/learning/) · [Runtime adapter](dal-naru/src/main/java/org/naruprof/runtime/) · [Application assembly](app/src/main/java/org/naruprof/app/)

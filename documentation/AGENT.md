# AGENT.md — NeuroProf / neuroprof-naru

> Instructions for contributors and AI coding agents working in the `neuroprof-naru` repository.
> Read this file before designing, generating, or modifying code. Prefer the actual repository configuration and established conventions over the illustrative paths and commands below.

## 1. Project overview

**NeuroProf** is a four-repository, technology-focused project that brings together JVM backend engineering, AI-agent execution, reactive communication, digital twins, and API gateway infrastructure. The system is to be designed using **The Pyramid Architecture** by Taha Ben Salah.

The overall project is divided into **four technology-centered tracks, each maintained in its own repository**:

| Repository | Primary technology | Responsibility |
| --- | --- | --- |
| **`neuroprof-naru`** (**this repository**) | [NARU](https://github.com/thevpc/naru), with [Nuts](https://github.com/thevpc/nuts) | Durable, actor-based AI-agent runtime integration; agent tasks, workflows, and orchestration through published application contracts. |
| `neuroprof-vert.x` | [Eclipse Vert.x](https://vertx.io/) | Reactive and non-blocking services, asynchronous messaging, event handling, and high-concurrency integration. |
| `neuroprof-eclipseDitto` | [Eclipse Ditto](https://eclipse.dev/ditto/) | IoT digital-twin integration, Things/Features/Policies, twin state synchronization, and device-facing messaging connectors. |
| `neuroprof-kong` | [Kong Gateway](https://developer.konghq.com/gateway/) | API entry point, upstream routing, traffic policies, security plugins, and gateway configuration. |

**Important architectural distinction:** These four repositories are *technology tracks*, not necessarily the four *business modules* of The Pyramid Architecture. The Pyramid's actual modules must be defined by cohesive business responsibilities, each with its own internal layers, facade, and owned data. Do not replace business boundaries with arbitrary framework boundaries. A technology track may host one or more Pyramid business modules, or provide drivers/adapters to them.

The precise NeuroProf business use cases, entities, external devices, deployment environment, and production-scale requirements are **not yet specified in this document**. Discover them in project requirements and contracts; do not invent them.

## 2. Mission of this repository

`neuroprof-naru` owns the **NARU-centered implementation and integration**. Its goals are to:

1. Run and coordinate agent tasks using NARU's JVM-native, actor-based runtime.
2. Expose well-defined **module facades** for capabilities that other parts of NeuroProf are allowed to invoke.
3. Interact with services in the other NeuroProf repositories only through declared public interfaces, transport clients, or versioned messages.
4. Support durable task lifecycles, observability, failure recovery, and testable orchestration without coupling business rules to transport or storage details.
5. Remain independently developable and testable; deploy independently when required.

NARU is an **agent runtime**, not a substitute for the Pyramid business-service layer or for a database. Prefer a thin NARU integration around explicit service contracts. In particular, do not use internal NARU persistence formats as a public application API.

### Non-goals

- Do not implement Kong's routing and plugin configuration here; that belongs in `neuroprof-kong`.
- Do not reproduce Vert.x's event-loop/runtime infrastructure here; that belongs in `neuroprof-vert.x`.
- Do not implement or bypass Eclipse Ditto's internal Thing database or authorization mechanisms here; access Ditto via supported APIs/connectors and the appropriate module facade.
- Do not make every NeuroProf component depend directly on NARU simply because this repository uses it.

## 3. Technology inventory and intended usage

The following technologies are in the **planned NeuroProf technology scope**. They are not all mandatory dependencies of every repository.

| Technology / practice | Intended role | Main location or boundary |
| --- | --- | --- |
| **Java / JVM** | Core implementation environment for Java-based services and agent integrations. | Primarily NARU, Vert.x, and any Java-based service adapters. |
| **NARU** | Durable AI sessions, actor-style concurrent tasks, event-driven agent behavior, and tool/workflow orchestration. | `neuroprof-naru`. |
| **Nuts** | Installation, execution, runtime dependency resolution, and lifecycle management for compatible JVM applications such as NARU. | NARU local tooling and deployment where applicable. |
| **Spring** | Dependency injection, application composition, and conventional JVM service infrastructure **where appropriate**. | Java backend components that choose Spring; do not require Spring inside NARU itself or Vert.x event-loop paths. |
| **JPA** | ORM-based relational persistence behind repository/DAL contracts. | `dal-jpa` implementations only, where relational persistence is needed. |
| **Servlet API** | Servlet-based HTTP handling for conventional Spring/Java web endpoints. | Servlet-based WS adapters only; **not** the Vert.x event-loop runtime. |
| **REST / HTTP** | Public synchronous APIs, management operations, and remote facade adapters. | WS REST endpoints and DAL HTTP/REST clients; potentially routed through Kong. |
| **gRPC** | Typed inter-service RPC, including appropriate streaming contracts. | WS gRPC endpoints and service gRPC client proxies. |
| **Asynchronous communication** | Commands, events, task completion, and decoupled integrations. | NARU durable events, Vert.x event bus, messaging drivers, and Ditto connectivity as applicable. |
| **Logback** | Structured application logging and operational diagnostics for Java services. | Java applications and adapters; log correlation IDs and failures, not secrets. |
| **Unit tests** | Fast validation of business logic, facades, validation, and adapters in isolation. | Each module and repository. |
| **Integration tests** | Verification across storage, API, messaging, and runtime boundaries. | Module integration suites and cross-repository scenarios. |
| **Circuit breaking and retries** | Bounded resilience for remote calls and transient failures. | Remote HTTP/gRPC/message adapters; use timeouts, backoff, and safe retry semantics. |
| **Kong Gateway** | API routing, gateway policies, plugins, and consumer-facing entry point. | `neuroprof-kong`. |
| **Eclipse Ditto** | JSON-based digital twins (`Things`), Features, Policies, and state synchronization. | `neuroprof-eclipseDitto`. |
| **Eclipse Vert.x** | Non-blocking HTTP/event-driven programming, verticles, and event bus integrations. | `neuroprof-vert.x`. |
| **The Pyramid Architecture** | Architectural rules for business modules, layering, facades, isolation, extensions, and deployment flexibility. | Every business module in the NeuroProf system. |

**Dependency policy:** Prefer the technologies above over introducing overlapping frameworks. Select versions from the existing build/configuration and verify compatibility before adding dependencies. Do not assume that an upstream project's roadmap feature is already released.

## 4. Architectural foundation: The Pyramid Architecture

Reference: [The Pyramid Architecture whitepaper](https://github.com/thevpc/the-pyramid-architecture/blob/main/whitepaper.md).

Follow its **four faces**:

1. **Structure:** autonomous business modules, consistent DAL → Service → WS layering, strict interfaces and compile-time boundaries.
2. **Interaction:** cross-module communication through published facades; extensions, drivers, and interceptors when cross-cutting coordination is needed.
3. **Elasticity:** keep module contracts stable so deployment can evolve along the **M modules / P processes** spectrum (modulith to microservices) without rewriting business logic.
4. **Convergence:** compose published capabilities into automation, workflows, and AI-driven interactions; NARU is relevant here but must respect all prior architectural rules.

### 4.1 Pyramid layers (inside a business module)

```text
               WS — REST / gRPC endpoints
               (depends on service-api)
                         |
                         v
               service-api — published facade
                         ^
                         | implemented by
               service-impl — business rules,
               orchestration, validations
                         |
                         v
               dal-api — repositories and
               external dependency contracts
                         ^
                         | implemented by
               dal-jpa / dal-http / dal-messaging / ...
                         |
                         v
               database / other services / broker

    infra — shared domain types, DTOs and references;
            independent of the other layers
```

The diagram expresses interface relationships rather than physical runtime call order. **Only the application assembly/composition root chooses concrete implementations.**

**Convergence and dependency rules:**

- WS controllers/handlers depend on `service-api`, **not** `service-impl` or database repositories.
- Business services use `dal-api` and published module facade contracts, **not** concrete JPA, HTTP, or messaging adapters.
- Concrete DAL adapters implement the `dal-api` interfaces and access external infrastructure.
- Repositories/storage adapters are private to their owning business module.
- No skipped layers, peer-to-peer internal bypasses, or dependency cycles. Build structure should enforce these constraints where practical.
- Between business modules, use the **target module's public facade**. Never call its internal service, DAO, ORM entity manager, or tables directly.
- Keep module-owned data segregated even when multiple modules share a database instance; use separate schemas or namespaces where appropriate.
- Scope ACID transactions to one business module. For cross-module side effects, use events/interceptors and explicit compensation or eventual consistency rather than assuming a single global transaction.

### 4.2 Proposed module anatomy

When a business capability is identified, use the following *pattern*, creating only the subprojects actually needed:

```text
<business-module>-infra                 # domain/shared types, DTOs, IDs
<business-module>-dal-api               # repository/external-service interfaces
<business-module>-dal-jpa               # optional JPA adapter
<business-module>-dal-http              # optional remote-service adapter
<business-module>-dal-messaging         # optional messaging adapter
<business-module>-service-api           # public module facade
<business-module>-service-impl          # business logic / orchestration
<business-module>-service-rest-cli      # optional remote facade proxy
<business-module>-service-grpc-cli      # optional remote facade proxy
<business-module>-ws-rest               # optional REST exposure
<business-module>-ws-grpc               # optional gRPC exposure
```

This is a **suggested layout, not a claim about files already present**. Names and build structure may be adapted to the actual codebase; the dependency constraints may not be relaxed. Keep the assembly/bootstrap layer separate from business logic.

## 5. Cross-repository integration

At a high level, the expected responsibilities are:

```text
             External clients / operators
                         |
                         v
               Kong API Gateway
           (public routes and policies)
                         |
           versioned public REST APIs
                         |
        +----------------+----------------+
        |                |                |
        v                v                v
    NARU track       Vert.x track     Ditto track
   durable agents    async/event      digital twins
   & workflows       processing       & state APIs
        |                |                |
        +------ public facades / contracts +
                and declared async messages
```

This is a **responsibility map, not a mandated deployment or single request path**. A request need not pass through all four repositories. gRPC, internal services, and messaging may use separate approved paths rather than Kong; validate gateway/protocol support before relying on it.

**Required integration principles:**

- Design and publish REST contracts (e.g., OpenAPI), gRPC contracts (`.proto`), and event schemas for the integrations actually used.
- Give operations stable names, clear ownership, request/response types, error semantics, and backward-compatible evolution rules.
- Pass correlation/trace IDs across synchronous and asynchronous boundaries; add idempotency keys to replayable commands.
- Treat remote services as unreliable: configure deadlines/timeouts, bounded retries with backoff/jitter, and circuit breakers where appropriate.
- **Never blindly retry non-idempotent operations** or assume exactly-once message delivery.
- A Vert.x event bus is not automatically a durable message broker; explicitly design persistence/recovery for messages that cannot be lost.
- Respect Ditto Policy-based authorization and published Thing APIs rather than accessing Ditto internal persistence.
- Keep Kong-specific settings and secrets in the gateway repository/configuration, not scattered through application business code.

## 6. NARU-specific implementation guidance

NARU ([upstream](https://github.com/thevpc/naru)) describes itself as a **durable, actor-based AI-agent runtime for the JVM**. Relevant features include persistent agent sessions, concurrent tasks/events, dynamic prompt construction, tool integration, and runtime state persistence. Nuts ([upstream](https://github.com/thevpc/nuts)) supplies a JVM application/package lifecycle mechanism.

For `neuroprof-naru`:

- Isolate NARU runtime interactions behind adapters/facades so core business logic can be tested without a running model or agent session.
- Model each agent capability with explicit inputs, outputs, failure states, permissions, and timeouts.
- Make long-lived agent workflows recoverable; test stop/restart/resume and duplicate-event scenarios where durability is a requirement.
- Use tool calls to published application capabilities rather than granting agents direct persistence access across modules.
- Validate and authorize every action an agent can perform. Treat prompts and external tool results as untrusted input.
- Document selected model/tool configuration and keep credentials out of the repository.
- Verify an upstream NARU/Nuts API or CLI feature against the installed version before using it; do not assume experimental or roadmap capabilities exist.
- Avoid adding Python bridges or subprocess-based orchestration when the supported JVM-native NARU integration already solves the problem.

## 7. Coding, operations, and quality standards

### Java and runtime concerns

- Prefer constructor injection, explicit interfaces, cohesive services, and small adapters.
- Keep Spring-specific annotations and Servlet dependencies at their appropriate composition/web boundaries; do not leak framework types into domain contracts unnecessarily.
- Put JPA entities, mappings, and transactions in the owning module's persistence implementation, not in unrelated modules.
- Avoid blocking Vert.x event-loop threads with JPA, synchronous HTTP, or long agent/model calls. Use the appropriate worker or asynchronous adapter on the Vert.x side.
- Use Logback consistently in Java services, with meaningful severity levels and structured/correlated context. Do not log passwords, tokens, personal secrets, or sensitive prompt content.
- Externalize addresses, ports, credentials, retry budgets, model settings, and integration endpoints into validated configuration.

### Test expectations

- **Unit:** business rules, facade validation, orchestration decisions, failure mappings, idempotency behavior, and retry/circuit-breaker policies with controlled fakes.
- **Integration:** JPA repositories where used, REST/gRPC serialization and endpoints, NARU runtime adapters, messaging, and Ditto/Vert.x/Kong boundaries as relevant.
- **Architecture:** automated checks where practical for forbidden dependencies, cycles, and cross-module data access.
- **End-to-end:** at least one realistic cross-repository workflow once the shared business use case and integration contracts are defined.
- Include negative and recovery tests: unavailable remote service, timeout, duplicate event, malformed payload, unauthorized operation, and restarted agent session.

### Build and run discipline

- Inspect the repository first (`README`, build descriptors, CI configuration, dependency lock/version definitions); use its actual build commands and version constraints.
- If this repository uses **Maven**, run the project's appropriate test lifecycle (for example, `mvn verify`) after code changes. Do not assume Maven, a JDK version, or a deployment file exists without inspection.
- If **Nuts** is configured, use its documented invocation for the pinned NARU artifact/version; do not invent package coordinates or installation scripts.
- Do not silently introduce Docker Compose, Kubernetes, a database vendor, a message broker, an LLM provider, or a new resilience library as a required dependency. Make such choices explicit in the relevant configuration/architecture decision.
- Run the narrow tests while iterating, then the relevant full test suites before considering a change complete. Report any tests that could not be run.

## 8. How AI coding agents should work here

When assigned a task:

1. **Locate the owner.** Determine whether the feature belongs in `neuroprof-naru` or one of the other three repositories. Do not cross repository responsibilities casually.
2. **Identify the business module.** Define or locate its published facade, data ownership, and interaction contract. A technology framework is not itself a business module.
3. **Respect the pyramid.** Place code in WS, Service, DAL API, or DAL implementation according to the rules above. Never shortcut a layer to finish a task quickly.
4. **Keep contracts first.** If a change crosses repositories, update/document the public schema and compatible client/server behavior; don't invent undocumented endpoints.
5. **Implement only needed adapters.** Favor a minimal vertical feature slice over speculative infrastructure or many empty modules.
6. **Prove behavior.** Add focused tests, including failure and recovery cases for remote or asynchronous workflows.
7. **Document decisions.** Update `README` and relevant `docs/` or architecture decision records when integrations, configuration, or boundaries change.
8. **Review scope.** Summarize modified files, technology choices, contract changes, test results, and any unresolved assumptions.

**Never** silently assume a specific NeuroProf domain model, downstream endpoint, port, database schema, protocol mapping, security model, or production environment. Use existing requirements as the source of truth, or label a proposal clearly as a proposal.

## 9. Definition of done

A feature is ready when it has an identified owning repository and business module, respects Pyramid dependency boundaries, uses versioned public contracts for cross-repository integrations, handles failures and logging appropriately, includes relevant unit/integration tests, documents new configuration, and has verified build/test results (or an explicit explanation of validation that was not possible).

## 10. References

- NeuroProf technology requirements supplied with the project (Spring, JPA, Servlet, REST, gRPC, asynchronous communication, Logback, unit/integration tests, circuit breaking/retries, Kong, Eclipse Ditto, Vert.x, NARU, Pyramid, and Nuts). Maintain these in a repository requirements document when available.
- [The Pyramid Architecture — repository](https://github.com/thevpc/the-pyramid-architecture)
- [The Pyramid Architecture — whitepaper](https://github.com/thevpc/the-pyramid-architecture/blob/main/whitepaper.md)
- [NARU — Nuts AI Reasoning Unit](https://github.com/thevpc/naru)
- [Nuts — JVM package/application manager](https://github.com/thevpc/nuts)
- [Eclipse Vert.x — core documentation](https://vertx.io/docs/vertx-core/java/)
- [Eclipse Ditto — overview](https://eclipse.dev/ditto/intro-overview.html)
- [Kong Gateway — documentation](https://developer.konghq.com/gateway/)

---
name: pyramid-architecture
description: Explain, design, implement, or review applications using Pyramid Architecture. Apply module boundaries, facade contracts, repository ownership, extensions and drivers, lifecycle hooks, deployment composition, and generation boundaries across languages and frameworks. Use when Pyramid Architecture is requested or already adopted by the target application.
license: CC-BY-SA-4.0
---

# Pyramid Architecture — agent skill

Apply Pyramid's ownership, contract, and dependency rules to the target application. This skill is independent of any particular framework, product, programming language, database, or repository layout.

The full reference is [Pyramid Architecture: concepts, implementation, and agent workflows](references/pyramid-architecture.md). Read it in full for comprehensive onboarding. For focused work, read the relevant sections:

| Task | Guide sections |
|---|---|
| Understand the model and choose business boundaries | 1–5 |
| Design data, queries, services, and consistency | 3, 6–8 |
| Choose deployment composition or tenancy | 9–10 |
| Establish generation ownership | 11 |
| Design portable contracts and adapt to an application stack | 12–13 |
| Follow a framework-neutral worked example | 14 |
| Implement a change, verify it, or diagnose a failure | 15–17 |
| Review conformance and adoption trade-offs | 18–20 |
| Understand provenance and portability | 21 |

## Adapt roles to the application

Identify the target application's language, framework, build/dependency system, composition roots, public contracts, generation sources, and project instructions. Use its existing names and supported mechanisms for the architectural roles below. Names such as `infra`, `dal-api`, and `service-api` are illustrative conventions, not required directory names.

The architecture can apply to web, mobile, desktop, command-line, worker, and other applications. Instantiate only needed capabilities: a stateless module needs no repository, a local application needs no HTTP server, and a single-tenant application needs no tenant subsystem. AI, BPM, generation, and process extraction are capabilities to adopt when useful. Preserve applicable boundaries without inventing unused layers or services.

## Preserve the architectural boundaries

1. Incoming adapters—web, UI, commands, or events—translate interactions into published facade operations. Keep domain rules and persistence access behind that facade.
2. Facades aggregate internal services. Business services use owning CRUD services for local entity operations. Give each entity repository one owner; other services access it through that owner.
3. Services depend on data-access contracts, never concrete storage adapters. Cross-module operations use the other module's published facade, never its repository, internal service, storage schema, or ORM relationships.
4. Keep shared contract types separate from implementation details. Keep persistence mappings and provider SDK types inside their adapters. Use references or deliberate DTOs for foreign relationships.
5. Modules consume extension APIs. Drivers implement extension provider contracts. Modules do not call concrete drivers; drivers do not call business modules or other drivers. Extensions coordinate implementors and may expose external entrypoints; drivers have no WS surface.
6. Keep transaction and data ownership boundaries module-local. Define outcomes and recovery for cross-module partial failures. Verify actual transaction completion rather than assuming an after-operation hook means committed.
7. Select concrete implementations and deployment topology in assembly/composition. Keep business logic out of application launchers and dependency bundles.
8. Establish generated ownership at file, method, or marked-region level. Edit the authoritative model, contract, manifest, or template and verify affected outputs.
9. Preserve identity, authorization, and correlation context across invocation boundaries, including tenant context where multitenancy applies. Installed code alone does not prove a capability is enabled or authorized.
10. Where automation exists, treat generated catalogs as capability descriptions. Implement argument, dependency, identity, and approval checks appropriate to the actual execution workflow.

## Carry out a task

1. Trace the affected path from caller through facade, service owner, data-access contract, adapter, and composition. Identify generated outputs and foreign-module dependencies.
2. Choose the owning component using the responsibility tables. Resolve module cycles through an appropriate shared extension or corrected business boundary.
3. Inspect actual APIs, lifecycle behavior, and tooling. Do not invent framework calls or copy commands from unrelated implementations.
4. Complete the requested change, including affected mappings, queries, contracts, generated artifacts, and composition declarations where applicable.
5. Verify relevant behavior and boundaries with the target project's supported checks. Include meaningful failure cases for affected persistence, module, network, or tenant boundaries.
6. Report the change, validation evidence, and unresolved implementation limits. Distinguish intended architecture from observed behavior and proposed improvements.

## Optional implementation evidence

[Implementation notes](references/implementation-notes.md) preserve analysis of Protos and PetStore, including their specific annotations, commands, repository layout, and deviations. Read this companion only when working on those systems or checking source evidence. It is not required for applying the architecture to another application and does not impose those systems' conventions.

## Attribution

Adapted from Taha BEN SALAH (2026), *The Pyramid Architecture Whitepaper v1.0*. This documentation adaptation is distributed under CC BY-SA 4.0. The general guide includes provenance and reuse guidance.

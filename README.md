# LedgerWatch

A banking microservice platform for tracking accounts and transactions, built as a learning/portfolio project demonstrating production-style microservice architecture on the JVM with a modern React frontend.

> **Status:** early scaffolding. `account-service` and `transaction-service` exist as Spring Boot skeletons with health endpoints and a Postgres instance via Docker Compose. Frontend, CI/CD, and AWS deployment are not yet implemented — this README describes the intended end state alongside what's actually built today.

## Problem statement

Most banking-domain side projects either stay monolithic (one Spring app, one database) or skip the operational concerns that make microservices hard in practice: schema ownership per service, eventual consistency between services, observability, and a real deployment pipeline. LedgerWatch is meant to be a realistic-but-small slice of a banking platform — account management and transaction processing as separately deployable services — so the interesting problems (service boundaries, data consistency, CI/CD, infra-as-code) actually show up instead of being designed away.

Concretely, the system needs to:
- Let a client open/view accounts and post/query transactions against them.
- Keep account and transaction data in separately owned schemas/datastores (no shared-database shortcut between services).
- Be deployable through a real pipeline to real infrastructure, not just `docker-compose up` on a laptop.
- Have a frontend that's testable in isolation (component-level via Storybook, behavior-level via Jest, end-to-end via Cypress) rather than only manually clicked through.

## Current state

| Component | Status |
|---|---|
| `account-service` (Spring Boot 3.3, Java 21) | Skeleton — boots, exposes a health endpoint |
| `transaction-service` (Spring Boot 3.3, Java 21) | Skeleton — boots, exposes a health endpoint |
| Postgres (local, via `docker-compose.yml`) | Running, seeded via `docker/postgres/init.sql` |
| React/TS frontend | Not started |
| CI/CD (GitHub Actions, Jenkins) | Not started |
| AWS infra (RDS, Aurora, EKS, Lambda) | Not started |

## Intended architecture

### Backend — Java / Spring Boot microservices

- **`account-service`** — owns account identity, balances, and account lifecycle (open/close/freeze). Source of truth for "does this account exist and what's its current balance."
- **`transaction-service`** — owns transaction history and posting logic (debits/credits, transfers). Validates against `account-service` (sync REST call or async event, TBD as the project grows) rather than reading its database directly.
- Each service owns its own schema/database — no cross-service joins. Inter-service communication starts as REST and is a candidate for event-driven (e.g., outbox + queue) once there's a real consistency problem to solve, not before.
- Shared concerns (correlation IDs, structured logging, health/readiness probes) live in each service rather than a shared library initially, to keep service boundaries honest.

### Frontend — React / TypeScript / Redux

- React + TypeScript SPA, Redux (likely Redux Toolkit) for client-side state — account list/detail views, transaction history, transfer/posting forms.
- **Storybook** for component-level development and visual review in isolation from the backend.
- **Jest** (+ React Testing Library) for unit/component behavior tests.
- **Cypress** for end-to-end flows against a running stack (open account → post transaction → see updated balance).

### Data layer / AWS path

Infra is meant to evolve in stages rather than jump straight to "production-grade":

1. **Local** — Postgres via Docker Compose (current state).
2. **RDS** — lift the same Postgres schema-per-service model into AWS RDS for a managed, single-instance cloud environment.
3. **Aurora** — move to Aurora (Postgres-compatible) once there's a reason to care about read replicas / failover characteristics beyond what RDS gives for free.
4. **EKS** — containerize each service and run them on EKS instead of wherever they started (e.g., ECS/local), once there's more than a couple of services and orchestration concerns (service discovery, rolling deploys, autoscaling) are worth the complexity.
5. **Lambda** — pull out the genuinely event-driven, spiky-load pieces (e.g., notification/webhook handling, scheduled reconciliation jobs) as Lambdas rather than running everything on EKS.

The point of staging it this way is to only pay for the next layer of infrastructure complexity when the project's actual needs justify it.

### CI/CD — GitHub Actions + Jenkins (dual pipeline)

- **GitHub Actions** — fast feedback on every PR: build, unit tests (Jest + JUnit), lint, and Cypress against an ephemeral environment. Gatekeeper for merging.
- **Jenkins** — deployment pipeline once code lands on the main branch: build images, push to a registry, and roll out to AWS (RDS/Aurora-backed environments, EKS deployments, Lambda packaging). Jenkins is the deploy authority; GitHub Actions is the merge gate.
- Rationale for running both rather than picking one: GitHub Actions for tight PR-loop ergonomics, Jenkins for the more involved, credential-heavy deploy orchestration against AWS — keeping deploy logic out of the same pipeline that every contributor's PR runs through.

## Repository layout

```
LedgerWatch/
├── account-service/        # Spring Boot service — account identity & balances
├── transaction-service/    # Spring Boot service — transaction posting & history
├── docker/
│   └── postgres/init.sql   # Local DB seed/schema
├── docker-compose.yml       # Local Postgres for development
└── pom.xml                  # Maven parent/aggregator (Java 21, Spring Boot 3.3.4)
```

(`frontend/`, `.github/workflows/`, `Jenkinsfile`, and `infra/` will be added as those pieces land.)

## Getting started (current state)

```bash
# Start local Postgres
docker-compose up -d

# Build and run a service (from repo root)
mvn -pl account-service spring-boot:run
mvn -pl transaction-service spring-boot:run
```

Each service exposes a health endpoint once running — check `application.yml` in each module for the configured port.

## Roadmap

- [ ] Account CRUD + balance logic in `account-service`
- [ ] Transaction posting + history in `transaction-service`, validating against accounts
- [ ] React/TS/Redux frontend scaffold with Storybook
- [ ] Jest unit tests + Cypress E2E against local stack
- [ ] GitHub Actions workflow: build, test, lint on PR
- [ ] Jenkins pipeline: image build + deploy
- [ ] Migrate local Postgres → AWS RDS
- [ ] RDS → Aurora migration
- [ ] Containerize services onto EKS
- [ ] Extract event-driven workloads to Lambda

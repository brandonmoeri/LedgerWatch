# LedgerWatch

A banking microservice platform for tracking accounts and transactions, built as a learning/portfolio project demonstrating production-style microservice architecture on the JVM with a modern React frontend.

## Current state

| Component | Status |
|---|---|
| `account-service` (Spring Boot 3.3, Java 21) | **Functional** — full account CRUD REST API, JPA persistence, validation, unit tests |
| `transaction-service` (Spring Boot 3.3, Java 21) | Skeleton — boots, exposes health endpoint (port 8082) |
| Postgres (local, via Docker Compose) | Running — seeded with `accounts` and `transactions` schemas |
| React/TS frontend | Not started |
| CI/CD (GitHub Actions, Jenkins) | Not started |
| AWS infra (RDS, Aurora, EKS, Lambda) | Not started |

## Architecture

Two separately deployable Spring Boot services, each owning its own Postgres schema — no shared-database shortcut. Inter-service communication starts as REST and is a candidate for event-driven (outbox + queue) once a real consistency problem exists.

- **`account-service`** (`:8081`) — account identity, balances, and lifecycle. Source of truth for account state.
- **`transaction-service`** (`:8082`) — transaction history and posting logic; will validate against `account-service` rather than touching its schema directly.

Planned frontend: React + TypeScript SPA with Redux Toolkit, Storybook for component isolation, Jest + Cypress for testing.

Planned infra path: local Postgres → AWS RDS → Aurora → EKS (containers) → Lambda (event-driven workloads).

## Repository layout

```
LedgerWatch/
├── account-service/        # Spring Boot — account identity & balances
├── transaction-service/    # Spring Boot — transaction posting & history
├── docker/postgres/init.sql  # Schema seed (accounts + transactions schemas)
├── docker-compose.yml       # Local Postgres
└── pom.xml                  # Maven parent (Java 21, Spring Boot 3.3.4)
```

## Getting started

```bash
# 1. Start local Postgres
docker-compose up -d

# 2. Run services (from repo root)
mvn -pl account-service spring-boot:run
mvn -pl transaction-service spring-boot:run
```

## account-service API  (`http://localhost:8081`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/accounts/{id}` | Fetch account by UUID |
| `POST` | `/accounts` | Create account (`ownerName`, optional `initialBalance`) |
| `PATCH` | `/accounts/{id}` | Update `ownerName` and/or `status` |
| `GET` | `/actuator/health` | Health probe |

Account statuses: `ACTIVE`, `FROZEN`, `CLOSED`.

## Running tests

```bash
mvn -pl account-service test
```

## Roadmap

- [x] Account CRUD + balance logic in `account-service`
- [ ] Transaction posting + history in `transaction-service`
- [ ] `transaction-service` validates accounts via REST call to `account-service`
- [ ] React/TS/Redux frontend scaffold with Storybook
- [ ] Jest unit tests + Cypress E2E against local stack
- [ ] GitHub Actions: build, test, lint on PR
- [ ] Jenkins: image build + deploy to AWS
- [ ] Migrate local Postgres → AWS RDS → Aurora
- [ ] Containerize services onto EKS
- [ ] Extract event-driven workloads to Lambda

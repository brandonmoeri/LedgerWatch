# LedgerWatch

A banking microservice platform for tracking accounts and transactions, built as a learning/portfolio project demonstrating production-style microservice architecture on the JVM with a modern React frontend.

## Current state

| Component | Status |
|---|---|
| `account-service` (Spring Boot 3.3, Java 21) | **Functional** — full account CRUD REST API, JPA persistence, validation, unit + integration tests |
| `transaction-service` (Spring Boot 3.3, Java 21) | **Functional** — full transaction CRUD REST API, JPA persistence, validation, unit + integration tests |
| Postgres (local, via Docker Compose) | Running — seeded with `accounts` and `transactions` schemas |
| React/TS frontend (Vite, React 19, Redux Toolkit) | **In progress** — accounts dashboard, detail, and create pages; Redux Toolkit store; Axios API client; Vitest component tests |
| CI/CD | Not started |
| AWS infra (`account-service`) | IaC ready (Terraform, `infra/aws/`) — deployed to ECS Fargate + RDS and CRUD-verified end-to-end; torn down after verification |

## Architecture

Two separately deployable Spring Boot services, each owning its own Postgres schema — no shared-database shortcut. Inter-service communication starts as REST and is a candidate for event-driven (outbox + queue) once a real consistency problem exists.

- **`account-service`** (`:8081`) — account identity, balances, and lifecycle. Source of truth for account state.
- **`transaction-service`** (`:8082`) — transaction history and posting logic; will validate against `account-service` rather than touching its schema directly.
- **`frontend`** — React + TypeScript SPA wired to `account-service`; Redux Toolkit for state, React Router for navigation.

Planned infra path: local Postgres → AWS RDS → Aurora → EKS → Lambda (event-driven workloads).

## Repository layout

```
LedgerWatch/
├── account-service/          # Spring Boot — account identity & balances
│   └── Dockerfile            # Multi-stage build (Maven reactor → JRE runtime image)
├── transaction-service/      # Spring Boot — transaction posting & history
├── frontend/                 # React/TS SPA — Vite, Redux Toolkit, React Router
├── docker/postgres/init.sql  # Schema seed (accounts + transactions schemas)
├── docker-compose.yml        # Local Postgres
├── infra/aws/                # Terraform — account-service AWS deployment
└── pom.xml                   # Maven parent (Java 21, Spring Boot 3.3.4)
```

## Getting started

```bash
# Start local Postgres
docker-compose up -d

# Run services (from repo root)
mvn -pl account-service spring-boot:run
mvn -pl transaction-service spring-boot:run

# Start the frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

## APIs

**`account-service`** (`http://localhost:8081`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/accounts` | List all accounts |
| `GET` | `/accounts/{id}` | Fetch account by UUID |
| `POST` | `/accounts` | Create account (`ownerName`, optional `initialBalance`) |
| `PATCH` | `/accounts/{id}` | Update `ownerName` and/or `status` |

Account statuses: `ACTIVE`, `FROZEN`, `CLOSED`.

**`transaction-service`** (`http://localhost:8082`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/transactions` | List all transactions |
| `GET` | `/transactions/{id}` | Fetch transaction by UUID |
| `POST` | `/transactions` | Post transaction (`accountId`, `type`, `amount`, optional `description`) |
| `PATCH` | `/transactions/{id}` | Update `status` and/or `description` |

Transaction types: `CREDIT`, `DEBIT`. Statuses: `POSTED`, `VOIDED`.

## AWS deployment (`account-service`)

Terraform config in `infra/aws/` deploys `account-service`: ECR → ECS Fargate → RDS Postgres, with DB credentials via Secrets Manager. No load balancer or NAT Gateway (cost-minimized). Torn down after CRUD verification.

```bash
cd infra/aws
terraform init
terraform apply -target=aws_ecr_repository.account_service

# build & push image
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com
docker build -f account-service/Dockerfile -t <ecr-repo-url>:latest .
docker push <ecr-repo-url>:latest

terraform apply -var "admin_cidr=<your-ip>/32" -target=aws_db_instance.main
docker run --rm -i postgres:16-alpine psql "postgresql://ledger:<db_password>@<rds_endpoint>:5432/ledgerwatch?sslmode=require" < docker/postgres/init.sql
terraform apply -var "admin_cidr=<your-ip>/32"
```

**Tearing down** (stops all billing): `terraform destroy -var "admin_cidr=<your-ip>/32"` from `infra/aws/`. Terraform state is local (`infra/aws/*.tfstate*`, gitignored) — the resources above are not currently running; this is the reproducible path to bring them back up.

Approximate cost if left running continuously: ~$1/day (RDS ~$12-13/mo, Fargate task ~$18/mo).

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

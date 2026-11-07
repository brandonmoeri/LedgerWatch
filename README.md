# LedgerWatch

A banking microservice platform for tracking accounts and transactions, built as a learning/portfolio project demonstrating production-style microservice architecture on the JVM with a modern React frontend.

## Current state

| Component | Status |
|---|---|
| `account-service` (Spring Boot 3.3, Java 21) | **Functional** — full account CRUD REST API, JPA persistence, validation, unit + integration tests |
| `transaction-service` (Spring Boot 3.3, Java 21) | **Functional** — full transaction CRUD REST API, JPA persistence, validation, unit + integration tests |
| Postgres + both services (local, via Docker Compose) | Running — `accounts` and `transactions` schemas owned by per-service Flyway migrations |
| React/TS frontend (Vite, React 19, Redux Toolkit) | **In progress** — JWT login, dashboard (Recharts balance-over-time and spend-by-type), accounts list/detail/create, and transactions pages; Redux Toolkit store; Axios API client; Storybook; Vitest + Testing Library + MSW + jest-axe tests |
| CI/CD | **CI functional** — GitHub Actions on PRs to `master`/`dev`: path-filtered per-service Spotless check + `mvn verify` (JaCoCo 70% coverage gate), frontend type check + lint + Vitest; Docker images published to GHCR on push to `dev`. Deployment (CD) not started |
| AWS infra | IaC ready (Terraform modules, `infra/aws/`) for both services on ECS Fargate + RDS. `account-service` was deployed and CRUD-verified on the earlier single-service config, then torn down; the two-service config has been planned but not yet applied |

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
│   └── Dockerfile            # Multi-stage build (Maven reactor → JRE runtime image)
├── frontend/                 # React/TS SPA — Vite, Redux Toolkit, React Router
├── docker/postgres/init.sql  # Schema seed (accounts + transactions schemas)
├── docker-compose.yml        # Postgres + account-service + transaction-service
├── infra/aws/                # Terraform — AWS deployment (network, ecs-service, rds modules)
└── pom.xml                   # Maven parent (Java 21, Spring Boot 3.3.4)
```

## Getting started

```bash
# Copy env template and fill in JWT_SECRET (32+ random bytes)
cp .env.example .env

# Start Postgres + account-service + transaction-service
docker compose up -d --build

# Start the frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

To run a service outside its container (e.g. for debugging), stop it in Compose and run it directly from the repo root: `mvn -pl account-service spring-boot:run` or `mvn -pl transaction-service spring-boot:run`.

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
| `POST` | `/transactions` | Accept a transaction (`accountId`, `type`, `amount`, optional `description`) and return `202` with status `PENDING`; send an `Idempotency-Key` header to make retries safe |
| `PATCH` | `/transactions/{id}` | Update `description`, or void a `POSTED` transaction |

Transaction types: `CREDIT`, `DEBIT`. Statuses: `PENDING`, `POSTED`, `REJECTED`, `VOIDED`. The balance is applied asynchronously: poll `GET /transactions/{id}` until the status is `POSTED` or `REJECTED`, which comes with a `rejectionReason` (`ACCOUNT_NOT_FOUND`, `ACCOUNT_NOT_ACTIVE` or `INSUFFICIENT_FUNDS`).

## AWS deployment

Terraform config in `infra/aws/` deploys `account-service` and `transaction-service` from one shared `ecs-service` module: ECR → ECS Fargate → RDS Postgres, with DB credentials and the JWT key via Secrets Manager. An Application Load Balancer is the public entry point: `/accounts` and `/auth` go to `account-service`, `/transactions*` to `transaction-service`, and any other path gets a 404, so `/internal/*` isn't exposed. It listens on plain HTTP only. `transaction-service` reaches `account-service` over Cloud Map private DNS. No NAT Gateway (cost-minimized).

### Remote state

Terraform state lives in S3 (`ledgerwatch/terraform.tfstate`), so it survives a lost laptop and is locked during writes. Locking uses S3 native lockfiles (`use_lockfile`), so there's no DynamoDB table; this needs Terraform ≥ 1.11. The bucket can't be managed through the backend it hosts, so `infra/aws/bootstrap/` creates it, keeping its own small state locally.

Do this once per AWS account, from `infra/aws/`:

```bash
# 1. Grant your IAM user access to the state bucket (needs IAM admin rights).
aws iam put-user-policy --user-name <your-iam-user> --policy-name ledgerwatch-tfstate --policy-document file://bootstrap/operator-policy.json

# 2. Create the bucket: ledgerwatch-tfstate-<account-id>
terraform -chdir=bootstrap init
terraform -chdir=bootstrap apply

# 3. Point this config at it. -migrate-state copies any existing local state across.
terraform init -migrate-state -backend-config="bucket=$(terraform -chdir=bootstrap output -raw state_bucket)"

# 4. Confirm the state is remote, then remove the local copies, which can hold secrets in plaintext.
terraform state pull
rm terraform.tfstate*
```

On a new machine or clone, skip all of that and pass the bucket name directly: `terraform init -backend-config="bucket=ledgerwatch-tfstate-<account-id>"`. The bootstrap's local state isn't needed for normal use. If it's lost, `terraform -chdir=bootstrap import aws_s3_bucket.state <bucket>` recovers the bucket, and the same goes for the other resources in `bootstrap/main.tf`.

If a crashed run leaves a stale lock, `terraform force-unlock <lock-id>` clears it (the ID is in the error message). To undo a bad state write, restore an earlier object version: they're kept for 90 days.

### Deploying

Requires the [remote state](#remote-state) bootstrap above.

```bash
cd infra/aws
terraform apply -target=module.account_service.aws_ecr_repository.this -target=module.transaction_service.aws_ecr_repository.this

# build & push both images from the repo root (repo URLs: terraform output ecr_repository_urls)
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com
docker build -f account-service/Dockerfile -t <account-service-repo-url>:latest .
docker build -f transaction-service/Dockerfile -t <transaction-service-repo-url>:latest .
docker push <account-service-repo-url>:latest
docker push <transaction-service-repo-url>:latest

terraform apply -var "admin_cidr=<your-ip>/32" -target=module.rds
docker run --rm -i postgres:16-alpine psql "postgresql://ledger:<db_password>@<rds_endpoint>:5432/ledgerwatch?sslmode=require" < docker/postgres/init.sql  # from the repo root
terraform apply -var "admin_cidr=<your-ip>/32"
curl -i "$(terraform output -raw alb_url)/accounts"  # 401 from account-service means routing works
```

**Tearing down** (stops all billing): `terraform destroy -var "admin_cidr=<your-ip>/32"` from `infra/aws/`. This leaves the state bucket in place: it costs pennies a month and has `prevent_destroy` set. The resources above are not currently running; this is the reproducible path to bring them back up.

Approximate cost if left running continuously: ~$2.15/day (RDS ~$12-13/mo, two Fargate tasks ~$36/mo, ALB ~$17/mo).

## Running tests

```bash
mvn -pl account-service test
```

## Roadmap

- [x] Account CRUD + balance logic in `account-service`
- [x] Transaction posting + history in `transaction-service`
- [ ] `transaction-service` validates accounts via REST call to `account-service`
- [x] React/TS/Redux frontend scaffold with Storybook
- [x] Unit/component tests (Vitest + Testing Library + MSW)
- [ ] Cypress E2E against local stack
- [x] GitHub Actions: build, test, lint on PR
- [x] GitHub Actions: publish service images to GHCR
- [ ] Jenkins: image build + deploy to AWS
- [x] Migrate local Postgres → AWS RDS (`account-service`, Terraform; verified then torn down)
- [ ] Migrate AWS RDS → Aurora
- [ ] Containerize services onto EKS
- [ ] Extract event-driven workloads to Lambda

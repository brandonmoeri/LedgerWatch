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
| AWS infra (`account-service`) | IaC ready (Terraform, `infra/aws/`) — deployed to ECS Fargate + RDS and CRUD-verified end-to-end; torn down after verification to avoid ongoing cost. See [AWS deployment](#aws-deployment-account-service) |

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
│   └── Dockerfile           # Multi-stage build (Maven reactor → JRE runtime image)
├── transaction-service/    # Spring Boot — transaction posting & history
├── docker/postgres/init.sql  # Schema seed (accounts + transactions schemas)
├── docker-compose.yml       # Local Postgres
├── infra/aws/                # Terraform — account-service AWS deployment
└── pom.xml                  # Maven parent (Java 21, Spring Boot 3.3.4)
```

## Getting started

```bash
# 1. Copy env file (edit values if needed)
cp .env.example .env

# 2. Start local Postgres
docker-compose up -d

# 3. Run services (from repo root)
mvn -pl account-service spring-boot:run
mvn -pl transaction-service spring-boot:run
```

## account-service API  (`http://localhost:8081`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/accounts/{id}` | Fetch account by UUID |
| `POST` | `/accounts` | Create account (`ownerName`, optional `initialBalance`) |
| `PATCH` | `/accounts/{id}` | Update `ownerName` and/or `status` |
| `GET` | `/health` | Health probe |

Account statuses: `ACTIVE`, `FROZEN`, `CLOSED`.

## AWS deployment (`account-service`)

Terraform config in `infra/aws/` deploys `account-service` end-to-end: ECR (image) → ECS Fargate (compute) → RDS Postgres (data), with DB credentials passed via Secrets Manager rather than plaintext env vars. No load balancer and no NAT Gateway, to keep this cheap to stand up for demos.

**Resources created:**

| Resource | Purpose |
|---|---|
| VPC (`10.0.0.0/16`) + Internet Gateway + 2 public subnets | Network for RDS and the Fargate task |
| Security groups | `rds-sg` — allows 5432 only from the ECS task's SG (+ optionally an admin CIDR for one-off `psql` access); `ecs-task-sg` — allows 8081 only from an admin CIDR (no ALB, so this is the direct access path) |
| RDS Postgres 16 (`db.t4g.micro`, 20GB gp3) | `ledgerwatch` database; schema/table DDL applied manually from `docker/postgres/init.sql` (same as local — `ddl-auto: none`) |
| ECR repository (`ledgerwatch/account-service`) | Holds the built container image |
| Secrets Manager secret (`ledgerwatch/account-service/db`) | JSON `{username, password, url}`, injected into the task as `DB_USERNAME`/`DB_PASSWORD`/`DB_URL` |
| ECS cluster + Fargate service + task definition (0.5 vCPU / 1GB) | Runs the container; task execution role is scoped to read only that one secret |
| CloudWatch log group | Container stdout/stderr, 3-day retention |

**IAM policies required on the deploying user**, beyond IAM/EC2/RDS/SecretsManager basics: `AmazonEC2ContainerRegistryFullAccess`, `AmazonECS_FullAccess`, `CloudWatchLogsFullAccess`. (App Runner was the original target instead of ECS, but new AWS accounts can hit an account-level `SubscriptionRequiredException` on App Runner until AWS finishes enabling the service — ECS Fargate has no such restriction.)

**Deploying:**

```bash
cd infra/aws
terraform init
terraform apply -target=aws_ecr_repository.account_service   # create ECR first

# build & push the image
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com
docker build -f account-service/Dockerfile -t <ecr-repo-url>:latest .   # from repo root
docker push <ecr-repo-url>:latest

terraform apply -var "admin_cidr=<your-ip>/32" -target=aws_db_instance.main   # bring up RDS

# apply the schema (ddl-auto is none, so this doesn't happen automatically)
docker run --rm -i postgres:16-alpine psql "postgresql://ledger:<db_password>@<rds_endpoint>:5432/ledgerwatch?sslmode=require" < docker/postgres/init.sql

terraform apply -var "admin_cidr=<your-ip>/32"   # everything else (secrets, ECS)
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

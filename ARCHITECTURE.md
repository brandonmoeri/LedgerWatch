# LedgerWatch — Architecture

## System overview

LedgerWatch is a small personal-finance ledger built as two independent Spring Boot microservices sharing a single Postgres instance (separate schemas), fronted by a React SPA, with the account service deployed to AWS via Terraform.

```
Browser
  └─ React SPA (Vite dev :5173 → proxy → :8081)
       └─ axios /api/*

account-service  :8081   ←──── Postgres ledgerwatch DB ────→  transaction-service  :8082
   schema: accounts                                                schema: transactions
```

---

## Repository layout

```
LedgerWatch/
├── pom.xml                        # Maven parent (Java 21, Spring Boot 3.3.4)
├── account-service/               # Spring Boot — account identity & balances
│   ├── Dockerfile                 # Multi-stage build (Maven reactor → JRE 21 Alpine)
│   └── src/main/resources/
│       ├── application.yml        # Default config (port 8081, local Postgres)
│       └── application-prod.yml   # Prod overrides (all credentials via env vars)
├── transaction-service/           # Spring Boot — transaction posting & history
│   └── src/main/resources/
│       └── application.yml        # Default config (port 8082, local Postgres)
├── frontend/                      # React 19 / TypeScript SPA
├── docker/postgres/init.sql       # Schema seed (accounts + transactions)
├── docker-compose.yml             # Local Postgres 16 only
└── infra/aws/                     # Terraform — account-service on ECS/RDS
```

---

## Backend services

### Maven parent (`pom.xml`)

| Property | Value |
|---|---|
| Group ID | `com.ledgerwatch` |
| Version | `0.1.0-SNAPSHOT` |
| Java | 21 |
| Spring Boot BOM | 3.3.4 |
| Modules | `account-service`, `transaction-service` |

The parent manages the Spring Boot dependency BOM and compiler plugin; each child uses `spring-boot-maven-plugin` to produce a fat jar.

---

### account-service

**Port:** `8081`  
**Postgres schema:** `accounts`  
**DDL policy:** `none` (schema owned by `init.sql`)

Key dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `postgresql` (runtime), `h2` (test).

#### Data model

```sql
accounts.account
  id          UUID PK  DEFAULT gen_random_uuid()
  owner_name  VARCHAR(255) NOT NULL
  status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'   -- ACTIVE | FROZEN | CLOSED
  balance     NUMERIC(19,4) NOT NULL DEFAULT 0
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
```

#### REST API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/accounts` | List all accounts |
| `GET` | `/accounts/{id}` | Fetch by UUID |
| `POST` | `/accounts` | Create (`ownerName`, optional `initialBalance`) |
| `PATCH` | `/accounts/{id}` | Update `ownerName` and/or `status` |

#### Configuration

| Profile | Source |
|---|---|
| default | `application.yml` — datasource falls back to `localhost:5432/ledgerwatch` with hard-coded dev credentials |
| `prod` | `application-prod.yml` — all three datasource values (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) **must** be set as environment variables; Hikari pool capped at 5 connections |

---

### transaction-service

**Port:** `8082`  
**Postgres schema:** `transactions`  
**DDL policy:** `validate`

Key dependencies: same as account-service. No service-to-service HTTP calls are implemented yet.

#### Data model

```sql
transactions.transaction
  id          UUID PK  DEFAULT gen_random_uuid()
  account_id  UUID NOT NULL   -- FK by convention; no DB constraint
  type        VARCHAR(10) NOT NULL        -- CREDIT | DEBIT
  status      VARCHAR(10) NOT NULL DEFAULT 'POSTED'   -- POSTED | VOIDED
  amount      NUMERIC(19,4) NOT NULL
  description VARCHAR(255)
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
```

#### REST API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/transactions` | List all transactions |
| `GET` | `/transactions/{id}` | Fetch by UUID |
| `POST` | `/transactions` | Create (`accountId`, `type`, `amount`, optional `description`) |
| `PATCH` | `/transactions/{id}` | Update `status` and/or `description` |

---

## Database

A single `ledgerwatch` Postgres 16 database hosts both services under isolated schemas:

- `accounts` — owned and migrated by account-service
- `transactions` — owned and migrated by transaction-service
- `pgcrypto` extension — provides `gen_random_uuid()` for PK generation

Schema is seeded via `docker/postgres/init.sql` on first container start. Neither service runs DDL migrations at runtime (`ddl-auto: none` / `validate`).

**Local:** `docker-compose.yml` runs `postgres:16-alpine` on `5432`, data persisted in a named volume `postgres_data`.

---

## Frontend

**Stack:** React 19 · TypeScript · Vite 8 · Redux Toolkit 2 · React Router 7 · Axios

### Structure

```
frontend/src/
├── api/
│   ├── client.ts       # Axios instance — baseURL /api, 10 s timeout, Bearer token interceptors
│   └── accounts.ts     # Typed wrappers around account-service CRUD endpoints
├── auth/
│   └── authStub.ts     # Placeholder token provider (no real auth yet)
├── pages/
│   ├── AccountsDashboardPage.tsx   # Lists all accounts via RTK thunk
│   ├── AccountDetailPage.tsx       # Single account view + PATCH form
│   └── CreateAccountPage.tsx       # POST /accounts form
├── store/
│   ├── store.ts           # RTK store — single `accounts` slice
│   └── accountsSlice.ts   # Async thunks: fetchAccounts, fetchAccountById, updateAccount
└── types/
    └── account.ts         # Account, UpdateAccountRequest interfaces
```

### Dev proxy

Vite rewrites `/api/*` → `http://localhost:8081/*` (strips `/api` prefix), so the SPA and account-service share the same origin in development without CORS configuration.

### Testing

Vitest + jsdom + `@testing-library/react`. Test setup in `src/test/setup.ts`.

---

## Infrastructure (AWS)

Terraform config in `infra/aws/` (Terraform ≥ 1.5, AWS provider ~5.0) deploys **account-service only**.

### Resources

| Resource | Detail |
|---|---|
| **VPC** | `10.0.0.0/16`, DNS support/hostnames enabled |
| **Subnets** | 2 public subnets across 2 AZs (`10.0.1.0/24`, `10.0.2.0/24`), route to IGW |
| **ECR** | `ledgerwatch/account-service` — mutable tags |
| **ECS Cluster** | `ledgerwatch-cluster` (Fargate) |
| **ECS Task** | Fargate, 0.5 vCPU / 1 GB, container port `8081`, logs to CloudWatch (`/ecs/ledgerwatch-account-service`, 3-day retention) |
| **ECS Service** | `desired_count = 1`, public IP assigned directly (no ALB) |
| **RDS** | Postgres 16, `db.t4g.micro`, 20 GB gp3, single-AZ, publicly accessible, no final snapshot |
| **Secrets Manager** | `ledgerwatch/account-service/db` stores `url`, `username`, `password` JSON; ECS task role reads it at startup |
| **Security groups** | ECS task: all egress open; ingress on `8081` gated by optional `admin_cidr` variable. RDS: inbound `5432` from ECS task SG only |

### Notable design choices

- **No load balancer or NAT Gateway** — cost-minimized; ECS tasks get public IPs directly.
- **RDS is publicly accessible** with a randomly generated 24-char password; access is locked to the ECS task's security group (and optionally a one-time admin CIDR for schema seeding).
- `backup_retention_period = 0` and `skip_final_snapshot = true` — this deployment is torn down after smoke-test verification.
- DB credentials are injected into the container as secrets using the ECS `secrets` field (resolves individual JSON keys from the Secrets Manager ARN at task startup).

### Terraform outputs

`ecr_repository_url`, `rds_endpoint`, `rds_port`, `ecs_cluster_name`, `ecs_service_name`, `db_secret_arn`, `db_password` (sensitive).

---

## Local development

```bash
# 1. Start Postgres (seeds schema on first run)
docker-compose up -d

# 2. Run services
mvn -pl account-service spring-boot:run    # http://localhost:8081
mvn -pl transaction-service spring-boot:run # http://localhost:8082

# 3. Start SPA (proxied to account-service)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## Testing

```bash
# Unit + integration tests (H2 in-memory for tests)
mvn test

# Frontend
cd frontend && npm test
```

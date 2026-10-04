# LedgerWatch — Architecture

## System overview

LedgerWatch is a small personal-finance ledger built as two independent Spring Boot microservices sharing a single Postgres instance (separate schemas, each migrated by Flyway), fronted by a React SPA, with the account service deployed to AWS via Terraform. Both services are stateless JWT resource servers that validate tokens signed with a shared HMAC secret; account-service also issues those tokens.

```
Browser
  └─ React SPA (Vite dev :5173 → proxy → :8081)
       └─ axios /api/*  (Authorization: Bearer <JWT>)

account-service  :8081   ←──── Postgres ledgerwatch DB ────→  transaction-service  :8082
   schema: accounts                                                schema: transactions
   issues + validates JWTs                                         validates JWTs
            └──────────── shared JWT_SECRET (HS256) ────────────────────┘
```

---

## Repository layout

```
LedgerWatch/
├── pom.xml                        # Maven parent (Java 21, Spring Boot 3.3.4)
├── common/                        # Shared library jar (RFC 7807 error handling)
├── account-service/               # Spring Boot — account identity, balances, login
│   ├── Dockerfile                 # Multi-stage build (Maven reactor → JRE 21 Alpine)
│   └── src/main/resources/
│       ├── application.yml        # Default config (port 8081, local Postgres)
│       ├── application-prod.yml   # Prod overrides (all credentials via env vars)
│       └── db/migration/          # Flyway migrations for the `accounts` schema
├── transaction-service/           # Spring Boot — transaction posting & history
│   ├── Dockerfile
│   └── src/main/resources/
│       ├── application.yml        # Default config (port 8082, local Postgres)
│       └── db/migration/          # Flyway migrations for the `transactions` schema
├── frontend/                      # React 19 / TypeScript SPA
├── docker/postgres/init.sql       # Enables pgcrypto only — all DDL is owned by Flyway
├── docker-compose.yml             # Postgres 16 + account-service + transaction-service
├── docs/adr/                      # Architecture decision records
├── .env.example                   # DB_* and JWT_SECRET template
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
| Testcontainers BOM | 1.21.4 (imported ahead of the Boot BOM — see [ADR-001](docs/adr/0001-testcontainers-for-integration-tests.md)) |
| Modules | `common`, `account-service`, `transaction-service` |

The parent manages the Spring Boot and Testcontainers BOMs, the springdoc version, Spotless (google-java-format), and JaCoCo (70% line-coverage gate). Each service uses `spring-boot-maven-plugin` to produce a fat jar.

---

### common

A plain jar (no Spring Boot plugin) that both services depend on. It currently holds `AbstractApiExceptionHandler`, the shared RFC 7807 `ProblemDetail` error contract:

| Exception | Status |
|---|---|
| `NoSuchElementException` | 404 |
| `IllegalStateException` | 409 |
| `IllegalArgumentException` | 400 |
| `MethodArgumentNotValidException` | 400, with an `errors` map of field → message |

Each service's `GlobalExceptionHandler` is a `@RestControllerAdvice` that extends it; account-service adds a `BadCredentialsException` → 401 handler for login. Both Dockerfiles build through the Maven reactor (`-pl <service> -am`), so `common` is compiled into each image, and CI rebuilds both services when `common/` changes.

---

### Security (both services)

Both services are stateless OAuth2 resource servers (`spring-boot-starter-oauth2-resource-server`) with near-identical `SecurityConfig` classes:

- **Token validation:** `NimbusJwtDecoder` with an HS256 key built from `jwt.secret` (the `JWT_SECRET` env var, at least 32 bytes). There is no default, so each service fails to start without it.
- **Roles:** the JWT's `roles` claim is mapped to Spring authorities with a `ROLE_` prefix, so `["ADMIN"]` becomes `ROLE_ADMIN`.
- **Sessions/CSRF:** stateless sessions, CSRF disabled.
- **Public paths:** `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`; account-service also permits `POST /auth/login`. Everything else requires a valid token.
- **Method security:** `@EnableMethodSecurity`; write endpoints are guarded with `@PreAuthorize("hasRole('ADMIN')")`.

| Role | Access |
|---|---|
| `ADMIN` | All reads and writes |
| `ANALYST` | Reads only (`GET`); writes return 403 |

**Token issuance** lives in account-service. `POST /auth/login` checks credentials against an in-memory dev user store in `AuthService` (BCrypt-hashed) and returns `{ token, tokenType: "Bearer", expiresIn: 3600 }`. Tokens carry `sub`, `roles`, `iat` and `exp` (1 hour). Seeded dev users:

| Username | Password | Roles |
|---|---|---|
| `admin` | `admin123` | `ADMIN` |
| `analyst` | `analyst123` | `ANALYST` |

There is no user database yet; replace the `AuthService` map with a real store when one exists. transaction-service only validates tokens, so both services must share the same `JWT_SECRET`.

---

### account-service

**Port:** `8081`  
**Postgres schema:** `accounts`  
**Migrations:** Flyway (`db/migration`, schema `accounts`); Hibernate `ddl-auto: none`

Key dependencies: `common`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-actuator`, `springdoc-openapi-starter-webmvc-ui`, `flyway-core` + `flyway-database-postgresql`, `postgresql` (runtime). Tests use `spring-boot-testcontainers`, Testcontainers `postgresql` and `spring-security-test`.

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

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/auth/login` | Public | Exchange `username`/`password` for a JWT |
| `GET` | `/accounts` | Any role | List accounts |
| `GET` | `/accounts/{id}` | Any role | Fetch by UUID |
| `POST` | `/accounts` | `ADMIN` | Create (`ownerName`, optional `initialBalance`) |
| `PATCH` | `/accounts/{id}` | `ADMIN` | Update `ownerName` and/or `status` |

#### Configuration

| Profile | Source |
|---|---|
| default | `application.yml`: datasource falls back to `localhost:5432/ledgerwatch` with hard-coded dev credentials; `JWT_SECRET` is required (no fallback) |
| `prod` | `application-prod.yml`: all three datasource values (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) **must** be set as environment variables; Hikari pool capped at 5 connections |
| `test` | `application-test.yml`: fixed 32-byte test `jwt.secret`; no datasource (Testcontainers supplies it) |

---

### transaction-service

**Port:** `8082`  
**Postgres schema:** `transactions`  
**Migrations:** Flyway (`db/migration`, schema `transactions`); Hibernate `ddl-auto: validate`

Key dependencies: same as account-service. It validates JWTs but does not issue them (no `/auth` endpoint). No service-to-service HTTP calls are implemented yet.

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

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/transactions` | Any role | List transactions |
| `GET` | `/transactions/{id}` | Any role | Fetch by UUID |
| `GET` | `/transactions/summary` | Any role | Dashboard summary |
| `POST` | `/transactions` | `ADMIN` | Create (`accountId`, `type`, `amount`, optional `description`) |
| `PATCH` | `/transactions/{id}` | `ADMIN` | Update `status` and/or `description` |

---

## Database

A single `ledgerwatch` Postgres 16 database hosts both services under isolated schemas:

- `accounts`: owned and migrated by account-service
- `transactions`: owned and migrated by transaction-service
- `pgcrypto` extension: provides `gen_random_uuid()` for PK generation

### Migrations (Flyway)

Each service owns its schema through Flyway. On startup, Spring Boot's Flyway auto-configuration applies `src/main/resources/db/migration/V*__*.sql` to that service's schema (`spring.flyway.schemas` / `default-schema`) and records history in a per-schema `flyway_schema_history` table, so the two services migrate independently against the same database.

- `V1__baseline.sql` in each service creates the schema, grants it to the `ledger` role, and creates the table shown above.
- Hibernate never generates DDL. account-service uses `ddl-auto: none`; transaction-service uses `validate` so it fails fast if entities drift from the migrated schema.
- `docker/postgres/init.sql` only enables `pgcrypto`; it no longer creates schemas or tables.
- Schema changes go in a new `V<n>__description.sql` in the owning service. Never edit an applied migration.
- `make migrate` runs `flyway:migrate` for both services through the Flyway Maven plugin against a running Postgres, without booting the apps (override `FLYWAY_URL` / `FLYWAY_USER` / `FLYWAY_PASSWORD`).

### Docker Compose

`docker-compose.yml` runs the full backend:

| Service | Image / build | Port | Notes |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | `5432` | Named volume `postgres_data`; `init.sql` mounted into `docker-entrypoint-initdb.d`; `pg_isready` healthcheck |
| `account-service` | `account-service/Dockerfile` | `8081` | Waits for `postgres` to be healthy; `DB_URL` points at `postgres:5432` |
| `transaction-service` | `transaction-service/Dockerfile` | `8082` | Same as above |

Both services read `JWT_SECRET` from the host environment or `.env` (copy `.env.example`). Flyway migrates each schema when its service starts. The SPA is not containerized; run it with Vite (see below).

---

## Frontend

**Stack:** React 19 · TypeScript · Vite 8 · Redux Toolkit 2 · React Router 7 · Axios

### Structure

```
frontend/src/
├── api/
│   ├── client.ts       # Axios instance — baseURL /api, 10 s timeout, Bearer token interceptors
│   ├── auth.ts         # POST /auth/login
│   └── accounts.ts     # Typed wrappers around account-service CRUD endpoints
├── auth/
│   ├── auth.ts         # JWT storage (localStorage), payload decode, expiry check
│   └── RequireAuth.tsx # Route guard — redirects to /login when no valid token
├── pages/
│   ├── LoginPage.tsx               # Username/password form → stores JWT
│   ├── AccountsDashboardPage.tsx   # Lists all accounts via RTK thunk
│   ├── AccountDetailPage.tsx       # Single account view + PATCH form
│   └── CreateAccountPage.tsx       # POST /accounts form
├── store/
│   ├── store.ts           # RTK store — single `accounts` slice
│   └── accountsSlice.ts   # Async thunks: fetchAccounts, fetchAccountById, updateAccount
└── types/
    └── account.ts         # Account, UpdateAccountRequest interfaces
```

### Authentication

`LoginPage` calls `POST /api/auth/login` and stores the returned JWT via `auth.setToken`. The Axios request interceptor attaches it as `Authorization: Bearer <token>` on every call. Protected routes are nested under `<RequireAuth />`, which decodes the token client-side and redirects to `/login` if it is missing or past `exp`. The decoded `roles` claim is available through `auth.getUser()`, but the server is the authority: role checks are enforced by `@PreAuthorize`, and 401/403 responses surface as `ApiError`.

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
# 0. Configure secrets (JWT_SECRET is required by both services)
cp .env.example .env

# 1a. Run Postgres + both services in Docker (Flyway migrates on startup)
make up                                          # docker compose up -d --build

# 1b. …or run Postgres in Docker and the services from Maven
docker compose up -d postgres
export JWT_SECRET=...                            # same value for both services
mvn -pl account-service -am spring-boot:run      # http://localhost:8081
mvn -pl transaction-service -am spring-boot:run  # http://localhost:8082

# 2. Start the SPA (proxied to account-service); log in as admin/admin123 or analyst/analyst123
cd frontend && npm install && npm run dev        # http://localhost:5173
```

Swagger UI is served at `/swagger-ui.html` on each service. Call `POST /auth/login` on account-service to get a token for the "Authorize" button.

## Testing

```bash
# Backend unit + integration tests (requires Docker; no running Postgres needed)
mvn test

# Frontend
cd frontend && npm test

# Both
make test
```

Backend integration tests run against real Postgres via **Testcontainers** ([ADR-001](docs/adr/0001-testcontainers-for-integration-tests.md)). Each service has a `TestcontainersConfiguration` that declares a `postgres:16-alpine` `PostgreSQLContainer` bean annotated `@ServiceConnection`. `@SpringBootTest` classes `@Import` it, and Spring Boot points the datasource at the container automatically. Flyway runs the real migrations against it, so tests exercise the production schema. The container uses database `ledgerwatch` and user `ledger` to match the `GRANT … TO ledger` in the baseline migrations. New integration tests should import the existing configuration rather than hardcode a datasource.

Security coverage per service:

- `JwtSecurityIntegrationTest`: requests with no token, a token signed with the wrong secret, or a tampered signature get 401; a validly signed token gets 200; `/actuator/health` is reachable without a token.
- `RoleAuthorizationIntegrationTest`: `ANALYST` gets 403 on writes; `ADMIN` succeeds.

CI runs on GitHub-hosted `ubuntu-latest` runners, which have Docker preinstalled, so no `services:` Postgres block is needed. JaCoCo enforces a 70% line-coverage minimum.

.PHONY: build test up down logs psql migrate seed seed-purge fmt fmt-check

# Backend: Maven multi-module reactor (account-service, transaction-service).
# Frontend: Vite/React app under ./frontend.

build:
	mvn -q -DskipTests clean package
	npm --prefix frontend run build

# Integration tests spin up their own Postgres via Testcontainers (see
# docs/adr/0001-testcontainers-for-integration-tests.md) — no `make up` needed first.
test:
	mvn clean test
	npm --prefix frontend test -- run

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f

# Requires `make up` (or an equivalent local Postgres) already running.
psql:
	docker compose exec postgres psql -U ledger -d ledgerwatch

# Applies each service's Flyway migrations directly against a running Postgres
# (make up), without booting the full Spring Boot app. Override connection
# details with e.g. `make migrate FLYWAY_URL=jdbc:postgresql://localhost:5432/ledgerwatch`.
FLYWAY_URL ?= jdbc:postgresql://localhost:5432/ledgerwatch
FLYWAY_USER ?= ledger
FLYWAY_PASSWORD ?= ledger

migrate:
	mvn -pl account-service,transaction-service flyway:migrate \
		-Dflyway.url=$(FLYWAY_URL) \
		-Dflyway.user=$(FLYWAY_USER) \
		-Dflyway.password=$(FLYWAY_PASSWORD)

# Loads ~6 months of demo data plus planted fraud scenarios (see README "Demo data"). Requires
# `make up` and the services to have started once, so Flyway has created the schemas. Re-runnable.
# Ground-truth labels go to scripts/seed/out/labels.json. Pass options with SEED_ARGS, e.g.
# `make seed SEED_ARGS="--end 2026-11-13 --fraud-mode enforce"`.
PYTHON ?= python
SEED_ARGS ?=
PSQL_PIPE = docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U ledger -d ledgerwatch

seed:
	$(PYTHON) scripts/seed/seed_demo_data.py $(SEED_ARGS) | $(PSQL_PIPE)

seed-purge:
	$(PYTHON) scripts/seed/seed_demo_data.py --purge | $(PSQL_PIPE)

fmt:
	mvn spotless:apply
	npm --prefix frontend run lint -- --fix

fmt-check:
	mvn spotless:check

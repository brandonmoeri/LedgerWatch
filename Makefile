.PHONY: build test up down logs psql migrate fmt fmt-check

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

fmt:
	mvn spotless:apply
	npm --prefix frontend run lint -- --fix

fmt-check:
	mvn spotless:check

# ADR-001: Use Testcontainers (@ServiceConnection) for integration tests

## Status

Accepted — 2026-09-13

## Context

`account-service` and `transaction-service` both had `@SpringBootTest` integration
tests that talked to Postgres, but the setup was half-finished and inconsistent:

- Neither service depended on Testcontainers.
- `application-test.yml` in both services hardcoded
  `jdbc:postgresql://localhost:5432/ledgerwatch` with the `ledger`/`ledger`
  credentials — tests only passed if a developer had already run
  `docker-compose up postgres` (or otherwise had a matching Postgres listening
  on 5432) before running `mvn test`. A clean clone failed integration tests
  out of the box.
- `account-service`'s CI workflow worked around this with a GitHub Actions
  `services:` block that started a `postgres:16` container on port 5432.
- `transaction-service` had no CI workflow at all, so it had neither the
  service-container workaround nor a working local path.

This needed to be resolved one way, not patched per-service. Two options were
considered:

1. **Adopt `@ServiceConnection`** (Spring Boot's Testcontainers integration):
   each test run starts its own ephemeral Postgres container and Spring Boot
   auto-configures the datasource against it. No hardcoded host/port/creds,
   no dependency on anything already running.
2. **Keep CI service containers**: extend the GitHub Actions `services:`
   pattern to `transaction-service` too, and document that local runs require
   `docker-compose up postgres` first.

## Decision

Adopt `@ServiceConnection` with Testcontainers in both services.

Each service has a `TestcontainersConfiguration` (`@TestConfiguration`) that
declares a `PostgreSQLContainer` bean annotated `@ServiceConnection`, imported
into `@SpringBootTest` classes via `@Import(TestcontainersConfiguration.class)`.
The container is configured with `withUsername("ledger")` /
`withDatabaseName("ledgerwatch")` to match what the Flyway baseline migrations
(`GRANT ... TO ledger`) expect.

CI's `services:` postgres block was removed from `account-service-ci.yml` —
GitHub's `ubuntu-latest` runners have Docker preinstalled, so Testcontainers
runs there without extra setup. A matching `transaction-service-ci.yml` was
added using the same pattern (it previously had none).

The root `pom.xml` pins `testcontainers.version` to `1.21.4` ahead of the
`spring-boot-dependencies` import (Boot 3.3.4 manages `1.19.8`), because the
docker-java client bundled with `1.19.8`/`1.21.3` gets an HTTP 400 from newer
Docker Desktop releases' `/info` endpoint. Verified locally with `mvn clean
test` in both services against Docker Desktop 4.79 (API 1.54) — all
integration tests pass end to end.

## Consequences

**Accepted cost:** every test JVM boots its own Postgres container, adding
roughly a few seconds of container startup per test run (mitigated by
Testcontainers' layer caching once the `postgres:16-alpine` image is pulled
once locally/in CI).

**Gained:** clean-clone reproducibility. `mvn verify` now passes on a fresh
checkout with nothing but Docker installed — no `docker-compose up` step, no
hardcoded credentials to keep in sync between `application-test.yml` and
`docker-compose.yml`, and local test runs now exercise the same container
lifecycle as CI. Both services are on an identical, symmetric setup, which
was the actual problem being fixed here.

This question is closed: new integration tests in either service should
import the existing `TestcontainersConfiguration`, not reintroduce a
hardcoded datasource URL or a CI-only service container.

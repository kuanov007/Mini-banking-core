# mini-bank-core

[![CI](https://github.com/kuanov007/mini-bank-core/actions/workflows/ci.yml/badge.svg)](https://github.com/kuanov007/mini-bank-core/actions/workflows/ci.yml)

Core banking service: customer accounts, a double-entry ledger, and concurrent-safe, idempotent money transfers.

## Tech stack

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1 — Web MVC, Data JPA, Validation, Actuator |
| Database | PostgreSQL 17, Flyway migrations |
| API docs | springdoc-openapi (Swagger UI) |
| Tests | JUnit, Mockito, MockMvc, Testcontainers |
| Delivery | Docker, Docker Compose, GitHub Actions |

## Run with one command

```bash
docker compose up --build
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health check: http://localhost:8080/actuator/health

The first build downloads dependencies inside Docker and takes a few minutes; later builds reuse the cache.

## Local development

Start only the database and run the app from the IDE or the terminal:

```bash
docker compose up -d postgres
export APP_JWT_SECRET=local-dev-jwt-secret-at-least-32-bytes   # Windows PowerShell: $env:APP_JWT_SECRET = "..."
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

PostgreSQL is published on **localhost:5433**, so it never clashes with a locally installed PostgreSQL on 5432.
The app refuses to start without `APP_JWT_SECRET` (at least 32 bytes). Set `APP_ADMIN_USERNAME` and `APP_ADMIN_PASSWORD` too if you need an admin user.

Prefer no Compose at all? Run `TestApplication` from `src/test/java` (or `./mvnw spring-boot:test-run`): it starts PostgreSQL in a throwaway container via Testcontainers and wires the app to it.

## Tests

```bash
./mvnw test      # unit + web-layer tests: fast, no Docker needed
./mvnw verify    # + integration tests against a real PostgreSQL (Docker required)
```

| Level | Example | Checks |
|---|---|---|
| Unit | `AuthServiceTest` | business logic, repository mocked with Mockito |
| Web slice | `AuthControllerTest`, `SecurityConfigTest` | validation, status codes, error format, 401/403 |
| Integration | `AuthApiIT`, `TransferApiIT` | HTTP → service → PostgreSQL round trip, Flyway migrations |
| Concurrency | `TransferConcurrencyIT`, `DeadlockIT` | 1 000 parallel transfers keep the ledger balanced; opposite lock order deadlocks, `AccountLocker` doesn't |

CI runs `./mvnw verify` and builds the Docker image on every push to `main` and on every pull request.

## API at a glance

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "azamat", "password": "s3cret-password", "fullName": "Azamat K"}'

curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "azamat", "password": "s3cret-password"}'

# With the accessToken from login, from one of your accounts to any customer account
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: 6f1e2d3c-4b5a-4978-8a6b-5c4d3e2f1a0b" \
  -H "Content-Type: application/json" \
  -d '{"fromAccount": "00000000000000000003", "toAccount": "00000000000000000004", "amount": 250.00, "description": "rent"}'
```

Every money-moving POST (`/api/transfers`, `/api/admin/deposits`, `/api/admin/withdrawals`) needs an `Idempotency-Key` header: a fresh UUID per logical request, reused only to retry that same request. A retry gets the original `201`, body and `Location` plus `Idempotent-Replayed: true`, and moves no money; the same key with a different request is `422 IDEMPOTENCY_KEY_REUSED`.

Every error comes back in the same [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) shape. A validation error:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/auth/register",
  "errors": { "username": "must be 3-50 characters: letters, digits, '.', '_' or '-'" }
}
```

A broken business rule, `throw new BusinessException("INSUFFICIENT_FUNDS", "Insufficient funds")`, becomes:

```json
{
  "title": "Business rule violated",
  "status": 422,
  "detail": "Insufficient funds",
  "instance": "/api/transfers",
  "code": "INSUFFICIENT_FUNDS"
}
```

## Project structure

```
src/main/java/uz/kuanov/minibank
├── Application.java
├── account/                          open, list, get accounts; admin freeze and unfreeze
├── auth/                             register, login, JWT issuing
├── ledger/                           operations and double-entry ledger: cash deposits, withdrawals, transfers
├── user/                             User entity, roles, admin bootstrap
└── common
    ├── config/OpenApiConfig.java     API info and bearer auth for Swagger UI
    ├── error/                        GlobalExceptionHandler, NotFoundException, BusinessException
    ├── security/                     SecurityConfig, JWT properties, 401/403 Problem Details
    └── validation/                   MaxUtf8Bytes constraint
src/main/resources/db/migration       Flyway migrations: V1__..., V2__...
src/test/java/uz/kuanov/minibank      TestcontainersConfig, TestApplication, tests per feature
```

## Design decisions

- **Flyway owns the schema; Hibernate only validates it** (`ddl-auto: validate`). If an entity and the migrations drift apart, the app refuses to start instead of failing at runtime.
- **Open Session in View is off.** Lazy loading can't leak SQL into the web layer; every transaction ends in the service.
- **One error format for everything.** RFC 9457 Problem Details with field-level validation messages and stable `code` values for business errors, so clients branch on codes instead of parsing messages.
- **Real PostgreSQL in integration tests, not H2.** Same SQL and the same migrations as production, while unit tests stay Docker-free and fast.
- **Package by feature.** Controllers are package-private.
- **`Instant` + `timestamptz`, truncated to microseconds** (PostgreSQL's precision), so a timestamp doesn't change after a round trip through the database.

# mini-bank-core

[![CI](https://github.com/YOUR_GITHUB_USERNAME/mini-bank-core/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_GITHUB_USERNAME/mini-bank-core/actions/workflows/ci.yml)

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
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

PostgreSQL is published on **localhost:5433**, so it never clashes with a locally installed PostgreSQL on 5432.

Prefer no Compose at all? Run `TestApplication` from `src/test/java` (or `./mvnw spring-boot:test-run`): it starts PostgreSQL in a throwaway container via Testcontainers and wires the app to it.

## Tests

```bash
./mvnw test      # unit + web-layer tests: fast, no Docker needed
./mvnw verify    # + integration tests against a real PostgreSQL (Docker required)
```

| Level | Example | Checks |
|---|---|---|
| Unit | `NoteServiceTest` | business logic, repository mocked with Mockito |
| Web slice | `NoteControllerTest` | validation, status codes, error format |
| Integration | `NoteApiIT` | HTTP → service → PostgreSQL round trip, Flyway migrations |

CI runs `./mvnw verify` and builds the Docker image on every push to `main` and on every pull request.

## API at a glance

```bash
curl -i -X POST http://localhost:8080/api/notes \
  -H "Content-Type: application/json" \
  -d '{"title": "First note", "content": "Hello"}'
```

Every error comes back in the same [Problem Details](https://www.rfc-editor.org/rfc/rfc9457) shape. A validation error:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/notes",
  "errors": { "title": "must not be blank" }
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
src/main/java/uz/kuanov/template
├── Application.java
├── common
│   ├── config/OpenApiConfig.java     API title and version for Swagger UI
│   └── error/                        GlobalExceptionHandler, NotFoundException, BusinessException
└── note/                             example feature, organised package-by-feature
src/main/resources/db/migration       Flyway migrations: V1__..., V2__...
src/test/java/uz/kuanov/template      TestcontainersConfig, TestApplication, tests per feature
```

## Design decisions

The defaults baked into this template. Keep the ones you can defend in an interview, and write your own for each project.

- **Flyway owns the schema; Hibernate only validates it** (`ddl-auto: validate`). If an entity and the migrations drift apart, the app refuses to start instead of failing at runtime.
- **Open Session in View is off.** Lazy loading can't leak SQL into the web layer; every transaction ends in the service.
- **One error format for everything.** RFC 9457 Problem Details with field-level validation messages and stable `code` values for business errors, so clients branch on codes instead of parsing messages.
- **Real PostgreSQL in integration tests, not H2.** Same SQL and the same migrations as production, while unit tests stay Docker-free and fast.
- **Package by feature.** Everything about notes lives in `note/`; controllers are package-private.
- **`Instant` + `timestamptz`, truncated to microseconds** (PostgreSQL's precision), so a timestamp doesn't change after a round trip through the database.

## Starting a new project

1. Once, in this repo's **Settings**, tick **Template repository**. Then **Use this template → Create a new repository**, e.g. `mini-bank-core`.
2. Rename the project: `artifactId` and `name` in `pom.xml`, `spring.application.name` in `application.yml`, and the package `uz.kuanov.template` → `uz.kuanov.<project>` (IntelliJ IDEA: right-click the package → Refactor → Rename).
3. Delete the example: the `note` packages in `main` and `test`, and `V1__create_note.sql`. Your own first migration becomes `V1__`.
4. In the CI badge at the top, replace `YOUR_GITHUB_USERNAME` and the repository name.
5. Rewrite this README for the project: what it does, an architecture diagram, `curl` examples and your own design decisions.

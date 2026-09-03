# url-shortener-backend

Spring Boot backend for the URL Shortener: the management API and the redirect service.

**The design lives elsewhere.** [url-shortener-kb](https://github.com/kamikaze011001/url-shortener-kb)
is the single source of truth — requirements, NFRs, architecture, data model, contract,
and the ADRs behind every decision. Where this code disagrees with that repository,
**this code is wrong**.

The API contract is vendored at [`contracts/openapi.yaml`](./contracts/openapi.yaml),
pinned to the merged KB revision recorded in [`contracts/REVISION`](./contracts/REVISION).

## Running locally

```bash
docker compose up -d          # Postgres on :5433, Redis on :6379
./gradlew bootRun             # app on :8080
```

Postgres is on host port **5433**, not 5432 — this machine already runs an unrelated
Postgres. Container-side it is still 5432.

```bash
curl http://localhost:8080/actuator/health
```

## Local topology mirrors production

Ports here play the role hostnames play in production, so deploying is two DNS records
and one environment variable — no code change.

| Production | Local | Role |
|---|---|---|
| `https://s.example.com/{code}` | `http://localhost:8080/{code}` | Redirect |
| `https://app.example.com/api/v1/*` | `http://localhost:8081/api/v1/*` | API (via Caddy) |

The short base URL is **configuration** (`app.short-base-url`), never derived from the
request. Deriving it is the most common way a URL shortener breaks on its first deploy:
it works locally and then hands out `http://localhost:8080/aB3xY9z` to real users.

## Modules

Enforced by Spring Modulith, not merely described — see
[ADR-0012](https://github.com/kamikaze011001/url-shortener-kb/blob/main/docs/adr/0012-modulith-verified-boundaries.md).

```
shared     ← open module: config, errors, client IP resolution
identity   ← Owners, registration, login, session
links      ← owns the Link and the links table; exposes port/LinkLookup
redirect   ← the hot path; reaches links and analytics through ports only
analytics  ← Clicks and statistics; exposes port/ClickRecorder (the ADR-0005 seam)
```

Each module splits by role: `port/` (the only cross-module surface), `usecase/`,
`domain/`, `store/`, `web/`. Only `port/` is annotated `@NamedInterface`, so everything
else is unreachable from other modules — and dependencies name the interface
(`"links::port"`), not the module. Reaching past it fails the build:

```bash
./gradlew test --tests '*ModularityTests*'
```

Verified to actually fail, not just to pass — a probe in `redirect` importing
`links.usecase.CreateLinkUseCase` produces:

```
Module 'redirect' depends on non-exposed type
com.sonanh.urlshortener.links.usecase.CreateLinkUseCase within module 'links'!
```

`ModularityTests` also writes C4 PlantUML diagrams to `build/spring-modulith-docs/`.

## Stack

| | |
|---|---|
| Java 21, Spring Boot 4.1.1, Gradle 9.7.1 | Boot 3.x is no longer offered by Initializr |
| Spring MVC + **virtual threads** | not WebFlux — ADR-0001 |
| Postgres 16 + Flyway | JPA for management, JdbcTemplate for the redirect lookup |
| Redis 7 | cache + rate limits only, never a source of truth — ADR-0004 |
| Spring Modulith 2.1.1 | `-core`, `-insight`, `-test`. Event registry deliberately excluded. |
| Micrometer Tracing (OTel bridge) | no exporter configured; `traceId` in MDC |
| Boot native structured logging | `logging.structured.format.console=ecs` in prod |

## Configuration

| Property | Local default | Notes |
|---|---|---|
| `app.short-base-url` | `http://localhost:8080` | `SHORT_BASE_URL` in production |
| `app.security.cookie-secure` | `false` | `true` in prod — `Secure` cookies cannot be set over plain HTTP |
| `app.security.jwt-secret` | dev placeholder | `JWT_SECRET` in production |
| `app.click-hash-salt` | dev placeholder | raw IPs are never stored or logged |
| `server.forward-headers-strategy` | unset | `native` in prod — trusts `CF-Connecting-IP` only because the process is reachable only through the tunnel |

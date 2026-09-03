# url-shortener-backend

Spring Boot backend for the URL Shortener: the management API and the redirect service.

**The design lives in [url-shortener-kb](https://github.com/kamikaze011001/url-shortener-kb).**
It is the single source of truth. Where this code disagrees with that repository, this
code is wrong. The API contract is vendored at `contracts/openapi.yaml`, pinned to the
merged KB revision in `contracts/REVISION` — change the contract there and re-vendor;
never edit the vendored copy.

## Commands

```bash
docker compose up -d                            # Postgres :5433, Redis :6379
./gradlew bootRun                               # app on :8080
./gradlew test --tests '*ModularityTests*'      # module boundaries
```

Postgres is on **5433**: 5432 is taken by an unrelated container on this machine.

## Where code goes

Two axes. **Modules** divide by subject; **role packages** divide by what a class talks
to. Every class has exactly one module and one role, so its path says what it is.

```
com.sonanh.urlshortener
├── shared/                     open module — every module may use all of it
│   ├── config/                 AppProperties, SecurityConfig, SharedConfig
│   ├── error/                  ApiException, ProblemCode, ApiExceptionHandler
│   └── http/                   ClientRequest
├── links/                      owns the Link and the links table
│   ├── port/                   ← the ONLY thing other modules may touch
│   ├── usecase/                business operations
│   ├── domain/                 behaviour with no edge (ShortCodeGenerator)
│   ├── store/                  database edge
│   └── web/                    HTTP edge: controller + wire records
├── redirect/                   the hot path — usecase/, web/
├── analytics/                  Clicks and statistics — port/, usecase/, store/
└── identity/                   Owners, registration, login, session
```

| Role package | Talks to | Holds |
|---|---|---|
| `port/` | other modules | Interfaces other modules call, and their record types |
| `usecase/` | — | One class per business operation |
| `domain/` | nothing external | Generators, screeners, encoders |
| `store/` | the database | Row mappers, writers, repositories, JPA entities |
| `web/` | HTTP | Controllers, request and response records |

A class in `store/` that formats an HTTP response, or one in `web/` that writes SQL, is
in the wrong package. That is the whole test.

### The boundary is enforced, not agreed

Modulith exposes a module's **base package** and treats sub-packages as internal, unless
a sub-package is annotated `@NamedInterface`. Only `port/` carries that annotation, so
**everything else in a module is genuinely unreachable from outside it** — moving a type
into or out of `port/` changes what other modules can compile against.

Dependencies name the interface, not the module:

```java
@ApplicationModule(allowedDependencies = { "links::port", "analytics::port", "shared" })
```

`"links"` would let `redirect` call `CreateLinkUseCase`; `"links::port"` does not.
Verified — a class in `redirect` referencing `links.usecase.CreateLinkUseCase` fails
`ModularityTests` with *"depends on non-exposed type"*.

**Cross-module access goes through a port**, never through another module's table. Two
modules querying one table is a real coupling the boundary test cannot see, which is why
it is forbidden by convention rather than caught by the build.

## Use cases, not services

One class per business operation, with a nested `Command` and `Result` and a single
`execute` method. There is no `LinkService`, and adding one is a defect.

```java
@Service
public class CreateLinkUseCase {
    public record Command(UUID ownerId, String destination, String alias, Instant expiresAt) {}
    public record Result(long id, String code, String shortUrl, ...) {}

    @Transactional
    public Result execute(Command command) { ... }
}
```

Rationale is [ADR-0011](https://github.com/kamikaze011001/url-shortener-kb/blob/main/docs/adr/0011-one-class-per-use-case.md).
Two rules follow from it:

- **Shared behaviour goes into a named collaborator with a real job** —
  `ShortCodeGenerator`, `DestinationScreener`, `ClickRecorder`. A class named `*Helper`
  or `*Util` in a feature package is the service layer growing back, and is a defect.
- **Anemic use cases are fine.** `ListLinksUseCase` wrapping one query is worth the file:
  every operation in the same shape is what makes the codebase navigable.

### Transactions

`@Transactional` goes on `execute()`. One business operation, one transaction.

`ResolveShortCodeUseCase` is the deliberate exception and says so in its javadoc: a
transaction there would let a failed Click insert mark the Redirect's transaction
rollback-only, so an analytics failure would reach back into a Redirect. **When a use
case is not transactional, its javadoc must say why** — otherwise the next reader adds
the annotation and reintroduces the bug.

`ClickRecorder` uses `REQUIRES_NEW` so that guarantee survives a future transactional
caller. A rule enforced only by every caller behaving is not enforced.

## Comments

Follow *A Philosophy of Software Design*: **a comment earns its place by holding
information the code cannot express.** Restating the code costs a line and teaches
nothing, and rots into a lie the first time the code changes without it.

**Write the interface comment first.** If describing what a class does takes a
paragraph of "and then, and also", the class does too much — the comment found the
design problem before the code did.

What to write:

- **Why, at the point of surprise.** `LinkWriter` uses `ON CONFLICT DO NOTHING`, and its
  javadoc explains that a constraint violation aborts the Postgres transaction so a
  caught exception cannot support a retry loop. Without that, the next reader
  "simplifies" it into a bug.
- **What a caller must know but cannot see**: what a method promises, what it forbids,
  what it does when things fail. `ClickRecorder` declares *must not throw, must not
  meaningfully block* — that is a contract, not a description.
- **What was rejected, when a reader would otherwise assume the opposite.** The unique
  index on `links.code` is deliberately not partial; that comment stops someone
  "optimising" a security hole into existence.
- **The ADR a decision came from.** One reference beats a paragraph of re-argument.

Two properties keep them honest:

- **Different abstraction from the code.** A comment restating the next line in English
  is a no-op. `// increment the counter` above `count++` is noise; *why* the counter is
  denormalised is information.
- **Placed where the reader is surprised**, not in a block at the top of the file. A
  comment far from what it explains is a comment nobody reads.

## Errors

One shape everywhere: RFC 9457 `application/problem+json`, produced only by
`ApiExceptionHandler`. Throw `ApiException` with a `ProblemCode` for expected failures;
anything else becomes an opaque `INTERNAL`, and stack traces never leave the process.

`ProblemCode` values are part of the API contract — the frontend switches on them, so
renaming one is a breaking change and belongs in the contract first. `title` and
`detail` are for humans and may be reworded freely.

**`NOT_FOUND` is deliberately overloaded.** Another Owner's Link is a `404`, not a
`403`, because `403` confirms it exists. The redirect path does the same: unknown,
deleted, disabled and expired are one identical response
([ADR-0008](https://github.com/kamikaze011001/url-shortener-kb/blob/main/docs/adr/0008-soft-delete-and-uniform-404.md)).

## Logging

One INFO line per meaningful business outcome, with a stable `event` field first:

```java
log.info("link.created code={} isCustomAlias={} ownerId={}", code, custom, ownerId);
log.info("link.redirect_missed code={} linkId={} reason={}", code, id, reason);
```

The event names are listed in
[03-architecture.md](https://github.com/kamikaze011001/url-shortener-kb/blob/main/docs/03-architecture.md).
`link.redirect_missed` records the real reason while the response stays a uniform 404 —
the log is where the debuggability that ADR-0008 costs is repaid.

**Passwords, JWTs, `Cookie` headers and raw IP addresses stay out of logs.** The system
promises IPs are never stored; writing one to a log file is storing it. Log the first
8 characters of `ip_hash` when correlation is needed.

## Facts a reader would otherwise get wrong

- **Spring Boot 4.1 and Spring Security 7.** The lambda DSL is the only DSL; `and()`
  chaining from 3.x examples does not exist. Most search results are for 3.x.
- **`app.short-base-url` is configuration**, never derived from the request. Deriving it
  works locally and then hands out `http://localhost:8080/aB3xY9z` to real users.
- **`CF-IPCountry` and `CF-Connecting-IP` do not exist locally.** Absence is normal:
  country falls back to `XX`, the IP falls back to the socket address.
- **`app.security.cookie-secure` is `false` locally.** `Secure` cookies cannot be set
  over plain HTTP, so a `true` default would break login on localhost only.
- **The redirect lookup uses `JdbcTemplate`, not JPA.** One row by unique index on a
  20 ms p99 path; a persistence context is pure overhead there.
- **`shell` pipes in this repo's tooling are unreliable** — `curl | grep` output gets
  mangled. Write to a file and parse it.

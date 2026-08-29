# CLAUDE.md

Read `README.md` for what the app does and how to run it. This file is only the
things that are easy to get wrong.

## Layout

- `apps/frontend` — Angular 20, npm workspace
- `apps/backend` — Spring Boot 3.5.7, Java 21, Maven (`mvnw`)
- `scripts/*.mjs` — the dev orchestration; `npm run dev:api` shells into
  `run-backend.mjs`, it does not call Maven directly

## Frontend conventions

These differ from Angular's older defaults. Match the existing files, not the
generator output you might remember:

- **No `.component.` in filenames.** A feature is `transactions/transactions.ts`,
  `transactions.html`, `transactions.css`. The class inside is still
  `TransactionsComponent`.
- **Plain CSS, not SCSS.** `styleUrl` (singular), not `styleUrls`.
- **Standalone components only.** There is no `NgModule` anywhere in `src/`.
  Declare dependencies in the component's own `imports`.
- **`inject()`, not constructor injection.** Fields are
  `private readonly x = inject(XService)`.
- **Signals for component state** (`signal`, `computed`, `effect`). RxJS is used
  at the edges — HTTP, and form-control streams piped through
  `takeUntilDestroyed()`. Don't reach for RxJS to hold state.
- **`protected readonly`** for anything the template touches; `private` for the
  rest.
- Shared helpers live in `core/utils` (`money.ts`, `api-error.ts`) and shared
  constants in `core/models`. Check there before writing a new formatter or
  error-message mapper.

## Backend conventions

- Package root is `com.lokesh_codes.expense_tracker_backend` — underscores, not
  camelCase.
- The DTO package is **`DTO`**, uppercase. Existing tooling and imports depend on
  it; don't quietly rename it.
- Errors are thrown as `NotFoundException` / `ConflictException` and turned into
  responses by `GlobalExceptionHandler`. Do not build `ResponseEntity` error
  bodies in a controller — a client mistake must not surface as a 500.
- Paging goes through `controller/PageableSupport.java`. Filtering, sorting and
  paging are evaluated in the database, never in Java over a full result set.
- Three profiles: `application.properties`, `-dev` (file-backed H2),
  `-prod` (real database, credentials from `apps/backend/.env`).

## Things to be careful about

- **Ports:** frontend 4300 (not 4200), API 8081. The dev server proxies `/api`,
  `/authenticate`, `/register` and `/actuator` — see `proxy.conf.json`. If you
  add a new top-level API path, add it there too or the browser will 404 in dev.
- **Budgets** store the cap only; spend is always derived from the transactions
  of the month being viewed. Never denormalise spend onto the budget row — past
  months have to stay truthful when a limit changes.
- **Money is multi-currency.** Never sum amounts across currencies into one
  total. Use `core/utils/money.ts` (`formatMoney`, `totalsByCurrency`).
- **Admins manage accounts, not money.** An admin endpoint must never return
  another user's transactions, budgets or categories. Check `README.md`'s
  admin section before extending anything under `admin/`.
- **Recurring rules catch up one period at a time**, so a dormant rule emits
  every entry it missed rather than one lump. Preserve that when touching the
  scheduler.
- **Tests run on H2; production runs on PostgreSQL, and they disagree.** Two
  bugs have now shipped green through the whole suite and failed on the first
  production request:
  - *Read-only transactions.* H2 treats `@Transactional(readOnly = true)` as a
    hint; PostgreSQL enforces it. A method that writes anything — including an
    audit row — must not be marked read-only. See
    `CsvApiTest.exportIsNotReadOnly`.
  - *Enum columns.* Hibernate pins the permitted values into the schema and
    never revisits them, so adding a value to an enum fails on every database
    that already exists. Every `@Enumerated(STRING)` field carries an explicit
    varchar `columnDefinition` for that reason, and `SchemaRepair` unpins
    databases created before it.

  Tests build the schema from scratch, so neither is visible to them. When
  touching transactions, enums or schema, assume the suite passing means less
  than it looks and check against a real PostgreSQL.
- `_src/` (outside this repo) holds the two pre-merge projects. It is history,
  not a build input — don't edit it, and don't copy code back out of it.

## Verifying

- `npm test` runs both suites; `npm run test:web` uses ChromeHeadless.
- The dev server is registered in `../.claude/launch.json` as **`web`**, so start
  it with the preview tooling rather than a raw `npm` command.

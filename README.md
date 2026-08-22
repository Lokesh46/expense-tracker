# Ledger — Expense Tracker

A personal expense tracker: an **Angular 20** frontend and a **Spring Boot 3.5**
REST API, in one repository.

This repo is the merge of two previously separate projects. The full history of
both is preserved — `git log` reaches every original commit, and `git blame`
still points at the commit that wrote each line.

| Was | Now |
|---|---|
| [`Lokesh46/ExpenseTracker-Backend`](https://github.com/Lokesh46/ExpenseTracker-Backend) | `apps/backend` |
| [`Lokesh46/expense-tracker-frontend`](https://github.com/Lokesh46/expense-tracker-frontend) | `apps/frontend` |

---

## Quick start

Requires **Node 20+** and a **JDK 17+** (21 recommended). No database server is
needed — local development runs on a file-backed H2 database.

```bash
npm install
npm run dev
```

| | URL |
|---|---|
| Frontend | http://localhost:4300 |
| API | http://localhost:8081 |
| H2 console (dev only) | http://localhost:8081/h2-console |

Port 4300 rather than Angular's default 4200, which is commonly already taken.

The dev server proxies `/api`, `/authenticate` and `/register` to port 8081, so
the browser only ever talks to one origin and CORS stays out of the way.

### Other commands

```bash
npm run dev:api      # backend only
npm run dev:web      # frontend only
npm run build        # build both
npm test             # run both test suites
```

---

## What it does

- **Transactions** — record spending, with filtering, search, sorting and paging
  all evaluated in the database.
- **Categories** — private to each account, seeded with eight starters on sign-up,
  each with a colour used consistently across charts and lists.
- **Budgets** — a monthly cap per category. The cap is stored; spend is derived
  from the transactions of whichever month you are looking at, so past months
  stay truthful when you change a limit.
- **Recurring** — rent, subscriptions and standing orders. Rules run overnight and
  also catch up when you open your ledger, one period at a time, so a rule left
  dormant produces every entry it missed rather than a single lump.
- **CSV import and export** — the parser copes with quoted fields, embedded
  commas, escaped quotes, several date layouts, thousands separators and
  parenthesised negatives, and reports bad rows individually instead of
  rejecting a whole statement over one line.
- **Light and dark themes** — both measured to clear WCAG AA contrast.

Amounts are formatted in the currency they were recorded in. Where a month mixes
currencies, the overview says so rather than presenting the sum of unlike
amounts as a total.

---

## Layout

```
apps/
  backend/     Spring Boot 3.5 · Java 21 · Spring Security + JWT · JPA
  frontend/    Angular 20 · standalone components · lazy routes · Chart.js
scripts/
  run-backend.mjs   Locates a JDK and launches Maven with the right flags
```

## Data and configuration

Local state lives in `apps/backend/data/` and is **not** committed:

| File | Purpose |
|---|---|
| `expensedb.mv.db` | The H2 database. Delete it to reset to an empty app. |
| `jwt-signing-key.json` | The RSA key used to sign tokens. **A secret.** |

The signing key is persisted deliberately. It used to be regenerated on every
boot, which silently invalidated every issued token whenever the service
restarted.

### Profiles

`dev` is the default. Production is selected with `SPRING_PROFILES_ACTIVE=prod`
and reads everything from the environment:

| Variable | Notes |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://host:5432/expense` |
| `DATABASE_USERNAME` | |
| `DATABASE_PASSWORD` | |
| `FRONTEND_URL` | Allowed browser origins, comma-separated. Patterns such as `http://localhost:[*]` are accepted. |
| `JWT_KEY_STORE` | Path to the signing key. Point it at a mounted volume — a container filesystem is wiped on redeploy, which would sign everyone out. |
| `PORT` | Defaults to 8081 |

The frontend's production API URL is compiled in, from
`apps/frontend/src/environments/environment.prod.ts`.

---

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/register` | — | Create an account (seeds starter categories) |
| `POST` | `/authenticate` | — | Exchange credentials for a JWT |
| `GET` | `/api/transactions` | Bearer | Search: `page`, `size`, `sort`, `categoryId`, `from`, `to`, `minAmount`, `maxAmount`, `paymentMethod`, `search` |
| `POST` | `/api/transactions` | Bearer | Record one |
| `GET/PUT/DELETE` | `/api/transactions/{id}` | Bearer | Single transaction |
| `GET` | `/api/transactions/export` | Bearer | CSV of everything matching the same filters |
| `POST` | `/api/transactions/import` | Bearer | Upload a CSV (multipart, max 5 MB) |
| `GET/POST` | `/api/categories` | Bearer | List / create |
| `GET/PUT/DELETE` | `/api/categories/{id}` | Bearer | Single category |
| `GET/POST` | `/api/budgets` | Bearer | List (`?month=yyyy-MM`) / create |
| `PUT/DELETE` | `/api/budgets/{id}` | Bearer | Single budget |
| `GET/POST` | `/api/recurring` | Bearer | List / create rules |
| `PUT/DELETE` | `/api/recurring/{id}` | Bearer | Single rule |
| `POST` | `/api/recurring/run` | Bearer | Generate anything already due |
| `GET` | `/actuator/health` | — | Liveness |

Everything is scoped to the authenticated account. A record belonging to someone
else returns **404, not 403** — "forbidden" would confirm that the id exists,
which is enough to enumerate another user's records.

---

## Tests

```bash
npm test
```

118 tests: 65 on the backend, 53 on the frontend.

The backend tests drive the application through MockMvc rather than calling
services directly, because the defects worth guarding against — the password
encoder wiring, the security filter chain, ownership checks — only exist once
the whole stack is assembled. They run against in-memory H2 and need no
database.

---

## Deploying

Three pieces: Postgres, the API, and the static frontend.

**No secret belongs in this repository.** `render.yaml` declares every sensitive
variable with `sync: false`, meaning Render prompts for it in the dashboard and
never reads it from here. The previous deployment passed database credentials as
Docker *build args*, which bakes them into the image metadata — they ended up
readable inside a public image. Set them at run time, never at build time.

### 1. Database

Use a provider whose free tier persists — Neon, for example. Render's own free
Postgres expires and is deleted, which is what ended the previous deployment.

Neon shows a URL beginning `postgresql://`. Java cannot use that directly; split
it into the three variables below and prefix the URL with `jdbc:`:

```
DATABASE_URL=jdbc:postgresql://ep-xxx.eu-central-1.aws.neon.tech/neondb?sslmode=require
DATABASE_USERNAME=<user>
DATABASE_PASSWORD=<password>
```

### 2. API

Point Render at this repository; `render.yaml` is picked up automatically. Then
set the four prompted variables:

| Variable | Value |
|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | from the step above |
| `FRONTEND_URL` | the exact frontend origin, e.g. `https://your-app.vercel.app`, no trailing slash |
| `JWT_SIGNING_KEY` | see below |

`JWT_SIGNING_KEY` matters more than it looks. A container filesystem is wiped on
every redeploy, so a file-based key would be regenerated each time and silently
sign out every user. Run the app locally once, then copy the whole contents of
`apps/backend/data/jwt-signing-key.json` into the variable.

The free plan sleeps after roughly 15 minutes of inactivity, so the first
request after a quiet spell takes about 50 seconds. Everything after that is
normal speed.

### 3. Frontend

Set the API address in `apps/frontend/src/environments/environment.prod.ts`,
commit, and let Vercel rebuild:

```ts
apiBaseUrl: 'https://expense-tracker-api.onrender.com',
```

Then set `FRONTEND_URL` on Render to the Vercel origin, or the browser is
refused by CORS with a bare 403 and no explanation.

### Checking it worked

```bash
curl https://YOUR-API.onrender.com/actuator/health
```

`{"status":"UP"}` means the service started and reached the database. A failure
here is almost always `DATABASE_URL` missing its `jdbc:` prefix.

---

## Troubleshooting

**`Unable to establish loopback connection` on startup (Windows).**
Java's NIO selector opens an internal self-pipe over an AF_UNIX socket inside
`java.io.tmpdir`. Some Windows machines refuse that bind inside
`%LOCALAPPDATA%\Temp` — usually endpoint protection or Controlled Folder
Access — and then *no* Java server can start, not just this one.
`scripts/run-backend.mjs` works around it by pointing
`jdk.net.unixdomain.tmpdir` at a directory beside the application. Running the
jar by hand needs the same flag:

```bash
java -Djdk.net.unixdomain.tmpdir=.tmp -jar apps/backend/target/expense_tracker_backend-0.0.1-SNAPSHOT.jar
```

**Maven picks the wrong Java.** `scripts/run-backend.mjs` searches for a JDK 17+
and sets `JAVA_HOME` itself, so an old JRE earlier on `PATH` does not matter.

**Port 4300 or 8081 already in use.** Change the frontend port in
`apps/frontend/angular.json` (`serve.options.port`) and the backend's with the
`PORT` environment variable. Update `apps/frontend/proxy.conf.json` to match.

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
npm run dev:api:prod # backend against a real database, from apps/backend/.env
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
- **Users and roles** — two roles, admin and member. An admin manages accounts:
  who exists, what they may do, and what they have been doing. It grants no sight
  of anyone's money — see below.
- **Activity log** — sign-ins, failed attempts, lockouts and account changes, with
  the address and client they came from. Searchable, filterable and exportable.
  Members see their own history without needing an admin to look for them.
- **Lockout** — five consecutive wrong passwords locks an account for fifteen
  minutes. It clears itself, or an admin can clear it early.
- **Light and dark themes** — both measured to clear WCAG AA contrast.

Amounts are formatted in the currency they were recorded in. Where a month mixes
currencies, the overview says so rather than presenting the sum of unlike
amounts as a total.

### What an admin can and cannot see

An admin sees accounts: usernames, emails, roles, status, sign-in history, and
**counts** — "142 transactions", "11 categories". They cannot open any of it.
No endpoint under `/api/admin` returns another account's transactions, budgets or
categories, and the DTOs have nowhere to put them even by accident. Every
ownership check in the application stays unconditional on role, which is why
`CurrentUserService` is still the only way those services resolve a user.

The activity log follows the same rule. It records security-relevant events and
account changes; ordinary use — recording an expense, opening a page — is never
logged. A log of what everyone did all day would be exactly the back door the
rest of this design avoids.

A member's ledger is private from you too. That is deliberate, and it is tested:
`AdminUserApiTest` asserts that an admin's own transaction list stays empty while
a member has rows, that a member's transaction fetched by id returns 404, and
that the user-detail response carries the counts but none of the descriptions or
amounts behind them.

### Roles taking effect

A JWT is a statement about the past: it says what someone could do when they
signed in, and keeps saying so until it expires. Left alone, that makes a user
management screen advisory — suspending an account or demoting an admin would
take effect up to ninety minutes later, whenever their token happened to run out.

So `AccountStateFilter` re-checks every authenticated request against the
database: the account still exists, is not suspended, is not locked, and the
token was issued after the account's last revocation. Authorities are rebuilt
from the stored role rather than read from the token, so a promotion or demotion
applies to the very next request. It costs one lookup on an indexed unique column
per request — a real cost, accepted knowingly, because most requests already load
the same row.

---

## Layout

```
apps/
  backend/     Spring Boot 3.5 · Java 21 · Spring Security + JWT · JPA
  frontend/    Angular 20 · standalone components · lazy routes · Chart.js
scripts/
  run-backend.mjs   Locates a JDK and launches Maven with the right flags
  setup-db-env.mjs  Converts a provider's connection string into apps/backend/.env
  load-env.mjs      Reads apps/backend/.env for the --prod check
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
| `JWT_SIGNING_KEY` | The signing key itself, as a JWK. **Set this in production.** Without it the key is regenerated on every deploy and every signed-in user is silently logged out. |
| `JWT_KEY_STORE` | Fallback path to a key file, used when `JWT_SIGNING_KEY` is unset. Fine locally; unreliable in a container, whose filesystem is wiped on redeploy. |
| `ADMIN_USERNAME` | A username to promote to admin on startup. See below. |
| `MAX_FAILED_ATTEMPTS` | Failed sign-ins before lockout. Defaults to 5. |
| `LOCKOUT_MINUTES` | How long a lockout lasts. Defaults to 15; 0 disables locking. |
| `ACTIVITY_RETENTION_DAYS` | How long audit entries are kept. Defaults to 180; 0 keeps everything. |
| `PORT` | Defaults to 8081 |

### The first administrator

A fresh database has no admin and no way to appoint one, since only an admin can
promote anybody. `ADMIN_USERNAME` is the way in:

1. Set `ADMIN_USERNAME` to the username you are about to use.
2. Register it through the UI. It is an admin the moment it is created — sign in
   and the admin screens are there.

The promotion is applied at registration *and* at startup, so it also works the
other way round: if the account already exists when you set the variable, the next
restart promotes it. That path additionally revokes its sessions, because a token
issued a moment earlier still claims to be a member.

Everyone else who registers is a member. The role in the request body is ignored —
only `ADMIN_USERNAME` can raise it, and that is set by whoever runs the
deployment, not by anything in the request. There is a test for both halves.

The promotion is written to the activity log with `system` as the actor, and it is
idempotent, so the variable can be left set without collecting a promotion per
deploy.

Worth being plain about the exposure: **whoever holds that username gets the
role.** If a stranger registers it before you do, the promotion lands on them. So
set it shortly before registering rather than leaving a guessable name configured
against an empty database indefinitely. That is also why it is declared
`sync: false` in `render.yaml` rather than written there in the open — naming the
administrator in a public repository tells anyone reading it which single account
is worth going after.

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
| `GET` | `/api/account/me` | Bearer | Your own account and role |
| `PUT` | `/api/account/email` | Bearer | Change your email (empty clears it) |
| `POST` | `/api/account/password` | Bearer | Change your password; ends every session |
| `GET` | `/api/account/activity` | Bearer | Your own sign-in history |
| `GET` | `/api/admin/users` | Admin | Search: `page`, `size`, `sort`, `search`, `role`, `status` |
| `POST` | `/api/admin/users` | Admin | Create an account, role included |
| `GET` | `/api/admin/users/stats` | Admin | Counts for the overview |
| `GET` | `/api/admin/users/{id}` | Admin | One account, with counts and recent activity |
| `PATCH` | `/api/admin/users/{id}` | Admin | Change email, role or status. Omitted fields are left alone |
| `POST` | `/api/admin/users/{id}/password` | Admin | Set a password; ends every session |
| `POST` | `/api/admin/users/{id}/unlock` | Admin | Clear a lockout early |
| `POST` | `/api/admin/users/{id}/revoke-sessions` | Admin | Sign out everywhere, password unchanged |
| `DELETE` | `/api/admin/users/{id}` | Admin | Delete the account and everything it owns |
| `GET` | `/api/admin/activity` | Admin | Search: `username`, `action`, `from`, `to`, `adverseOnly` |
| `GET` | `/api/admin/activity/export` | Admin | CSV of the same, capped at 10,000 rows |
| `GET` | `/actuator/health` | — | Liveness |

Everything is scoped to the authenticated account, whatever its role. A record
belonging to someone else returns **404, not 403** — "forbidden" would confirm
that the id exists, which is enough to enumerate another user's records.

The admin routes are gated twice: by the path rule in `JwtSecurityConfig`, and
again by `@PreAuthorize` on every method of `UserAdminService`. The duplication is
deliberate — a path rule is one typo away from being open, and typos in path rules
produce no error.

Some deliberate status codes:

| Code | When |
|---|---|
| `401` | No token, or a token whose account is gone, suspended, locked or revoked |
| `403` | Signed in but not an admin — or a suspended account trying to sign in |
| `409` | A guard refused: last remaining admin, acting on yourself, wrong current password |
| `423` | Locked out after too many failed attempts, with the remaining time |

A guard refusal is a `409` rather than a `400` because nothing about the request
is malformed; it is the state of the system that forbids it. And a wrong current
password when changing your own is a `409`, not a `401`: the request *is*
authenticated, and a `401` would make the client throw away a valid token.

---

## Tests

```bash
npm test
```

214 tests: 131 on the backend, 83 on the frontend.

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
Put the database in the **same region** as the API; a cross-region hop adds
100-200 ms to every query, and a page makes several.

Neon shows a URL beginning `postgresql://` with the credentials embedded. Java
cannot use that directly. Add the `jdbc:` prefix, take the credentials out of
the URL, and keep `?sslmode=require`.

Rather than doing that by hand, paste the provider's string into the converter:

```bash
npm run setup:db
```

It reads the string from stdin — so it stays out of shell history — splits it
into the three values, adds the `jdbc:` prefix, keeps `sslmode`, and drops
parameters the JDBC driver does not use (Neon appends `channel_binding`). It
warns if you pasted a pooled `-pooler` host, which breaks prepared statements
under Spring Boot's own connection pool.

Then check it actually reaches the database:

```bash
npm run dev:api:prod
```

`apps/backend/.env` is gitignored. The script refuses to start on the mistakes
that are otherwise diagnosed from an obscure JDBC error — a missing `jdbc:`
prefix, credentials left inside the URL, or an unfilled placeholder — and never
prints the values. It listens on port 8082, so it can run alongside the normal
dev server.

Once `curl http://localhost:8082/actuator/health` returns `{"status":"UP"}`, the
same four values go into the hosting provider.

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

### Keeping it warm

The free plan spins the instance down after about 15 minutes of inactivity, and
waking it takes roughly 50 seconds -- long enough that the app looks broken
rather than slow.

`.github/workflows/keep-alive.yml` pings `/actuator/health` every 10 minutes
between 06:30 and 00:30 IST. Waking hours only, on purpose: free instances get
750 hours a month and a month is about 730, so pinging around the clock would
consume the whole allowance. Waking hours costs roughly 540 and leaves headroom.
The same request keeps the Neon database warm, since its compute also suspends
when idle.

Two honest limitations:

- GitHub queues scheduled workflows at low priority and can delay them by 15
  minutes or more, so an occasional cold start still gets through.
- GitHub disables scheduled workflows in repositories with no activity for 60
  days. If the app suddenly feels slow again, check the Actions tab first.

If cold starts stop being acceptable, the options are a host that does not sleep
(Fly.io wakes in a few seconds; an Oracle Cloud always-free VM does not sleep at
all) or Render's paid plan.

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

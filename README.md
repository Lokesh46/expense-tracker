# Expense Tracker

A personal expense tracker: **Angular 20** frontend and a **Spring Boot 3.5** REST API,
kept in one repository.

This repo is the merge of two previously separate projects. The full commit
history of both is preserved — `git log` reaches every original commit, and
`git blame` still points at the commit that wrote each line.

| Was | Now |
|---|---|
| [`Lokesh46/ExpenseTracker-Backend`](https://github.com/Lokesh46/ExpenseTracker-Backend) | `apps/backend` |
| [`Lokesh46/expense-tracker-frontend`](https://github.com/Lokesh46/expense-tracker-frontend) | `apps/frontend` |

---

## Quick start

Requires **Node 20+** and a **JDK 17+** (21 recommended). No database server
needed — local development runs on a file-backed H2 database.

```bash
npm install
npm run dev
```

That starts both halves:

| | URL |
|---|---|
| Frontend | http://localhost:4300 |
| API | http://localhost:8081 |
| H2 console (dev only) | http://localhost:8081/h2-console |

The dev server proxies `/api`, `/authenticate` and `/register` to port 8081, so
the browser only ever talks to one origin and CORS stays out of the way.

### Running each half on its own

```bash
npm run dev:api
npm run dev:web
```

---

## Layout

```
apps/
  backend/     Spring Boot 3.5 · Java 21 · Spring Security + JWT · JPA
  frontend/    Angular 20 · standalone components · Chart.js
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
| `FRONTEND_URL` | Exact origin allowed by CORS |
| `JWT_KEY_STORE` | Path to the signing key. Point at a mounted volume — a container filesystem is wiped on redeploy, which would log everyone out. |
| `PORT` | Defaults to 8081 |

The frontend's production API URL is compiled in, from
`apps/frontend/src/environments/environment.prod.ts`.

---

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/register` | — | Create an account |
| `POST` | `/authenticate` | — | Exchange credentials for a JWT |
| `GET/POST` | `/api/categories` | Bearer | List / create categories |
| `GET/PUT/DELETE` | `/api/categories/{id}` | Bearer | Single category |
| `GET/POST` | `/api/transactions` | Bearer | List / create transactions |
| `GET/PUT/DELETE` | `/api/transactions/{id}` | Bearer | Single transaction |
| `GET` | `/actuator/health` | — | Liveness |

Transactions are scoped to the authenticated user; one user cannot read or
modify another's. Categories are currently shared across all users.

---

## Troubleshooting

**`Unable to establish loopback connection` on startup (Windows).**
Java's NIO selector opens an internal self-pipe over an AF_UNIX socket inside
`java.io.tmpdir`. Some Windows machines refuse that bind inside
`%LOCALAPPDATA%\Temp` — usually endpoint protection or Controlled Folder
Access — and then *no* Java server can start. `scripts/run-backend.mjs` works
around it by pointing `jdk.net.unixdomain.tmpdir` at the repo-local `.tmp/`.
Running the jar by hand needs the same flag:

```bash
java -Djdk.net.unixdomain.tmpdir=./.tmp -jar apps/backend/target/*.jar
```

**Maven picks the wrong Java.** `scripts/run-backend.mjs` searches for a JDK 17+
and sets `JAVA_HOME` itself, so an old JRE earlier on `PATH` does not matter.

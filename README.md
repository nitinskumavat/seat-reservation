# Seat Reservation

A JSON API that sells assigned seats for a show and stays correct when thousands of buyers hit
"book" at once:

- a seat is never sold twice; a race for one seat has exactly one winner, everyone else gets a clean `409`
- a user never holds more than the show's `per_user_limit` seats (default 4)
- a retried request with the same idempotency key never reserves twice

Java 21 · Spring Boot 4.1 · Postgres 17 · plain SQL via `JdbcTemplate`

| Document | What's in it |
|---|---|
| [DESIGN.md](DESIGN.md) | Schema, the exact reserve and cancel transactions, every idempotency case |
| [WRITEUP.md](WRITEUP.md) | Why it is race-free, idempotency, holds, consistency, alerting, AI usage, next steps |

**Live URL:** not deployed yet. **Live logs recording:** to be added after deployment.

## How each correctness requirement is met

| Requirement | Mechanism | Proven by |
|---|---|---|
| No seat confirmed to two users | Seats are row-locked (`SELECT … FOR UPDATE`) in label order inside one transaction; a waiter re-reads the row and sees it taken | `ReservationConcurrencyTest` (500 racers → 1 winner); burst phases 1–2 |
| Zero 5xx | Every decline is a 4xx domain outcome (`409` with a `reason`) | Burst fails on any 5xx |
| `available + held + confirmed == total_seats` | Seats change only inside transactions; `GET /shows/{id}` counts them in one query | Burst polls the show during the stampede |
| Idempotent retries | The key is claimed with `INSERT … ON CONFLICT` in the same transaction as the seats; a stored request hash detects a changed body | `IdempotencyTest`; burst phases 2–3 |
| Per-user limit under concurrency | Conditional update on a per-(user, show) counter row: `seat_count + n <= limit` | `PerUserLimitTest` (10 parallel on limit 4 → 4); burst phase 4 |
| Identity from the token | The user is the JWT `sub`; body fields are ignored; cancel looks up `(id, user)` | `ReservationControllerTest`, `CancelTest`; burst phase 5 |

The reasoning behind each mechanism is in [WRITEUP.md](WRITEUP.md); the exact transactions are in
[DESIGN.md](DESIGN.md).

## Quick start

Requires Docker with Compose v2. Ports `8080` (API) and `5433` (Postgres) must be free.
Java 21+ is needed only for the tests and the burst script. The first build takes a few minutes
while Maven downloads dependencies.

```bash
docker compose up --build -d
curl localhost:8080/actuator/health/readiness      # {"status":"UP"}
```

Stop with `docker compose down`. The database is discarded, so the next start is empty.

Optional live dashboard (Prometheus + Grafana): see [Dashboard](#dashboard).

## Walkthrough

Copy and paste the block below. It needs `jq`. Each run uses fresh user names, so you can run it
again; idempotency keys are per user.

```bash
BASE=http://localhost:8080
RUN=$RANDOM

# 1. Admin creates a show. The local admin token is dev-admin-token.
SHOW_ID=$(curl -s -X POST $BASE/shows -H 'X-Admin-Token: dev-admin-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A12"],"price_paise":25000}' | jq -r .id)

# 2. Get tokens. Demo auth: any user id works, and the user is created on first use.
token() { curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d "{\"user_id\":\"$1\"}" | jq -r .token; }
ALICE=$(token alice-$RUN)
BOB=$(token bob-$RUN)

reserve() { curl -s -X POST $BASE/shows/$SHOW_ID/reserve -H "Authorization: Bearer $1" \
  -H 'Content-Type: application/json' -d "$2" | jq -c .; }
cancel()  { curl -s -X POST $BASE/reservations/$2/cancel -H "Authorization: Bearer $1" | jq -c .; }

# 3. Alice reserves A12 → 201 confirmed
RES=$(reserve $ALICE '{"seats":["A12"],"idempotency_key":"k1"}'); echo "$RES"
RES_ID=$(jq -r .reservation_id <<< "$RES")

# 4. Same request again → the same reservation (idempotent replay; nothing new is booked)
reserve $ALICE '{"seats":["A12"],"idempotency_key":"k1"}'

# 5. Same key, different seats → 409 key_reused
reserve $ALICE '{"seats":["A1"],"idempotency_key":"k1"}'

# 6. Bob wants A12 → 409 seat_taken. The user_id in the body is ignored; identity comes from the token.
reserve $BOB '{"seats":["A12"],"idempotency_key":"b1","user_id":"alice"}'

# 7. Show state → available 3, held 0, confirmed 1
curl -s $BASE/shows/$SHOW_ID | jq -c '{total_seats, counts}'

# 8. Bob cannot cancel Alice's reservation → 404 not_found; Alice can → status "cancelled"
cancel $BOB $RES_ID
cancel $ALICE $RES_ID
```

A successful reservation (step 3) and a decline (step 6) look like this:

```json
{"reservation_id":"3f0c…","show_id":"9b1e…","user_id":"alice","seats":["A12"],"amount_paise":25000,"status":"confirmed"}
{"reason":"seat_taken","message":"one or more requested seats are not available"}
```

## API

| Endpoint | Auth | Success |
|---|---|---|
| `POST /auth/token` `{user_id}` | none | `200 {token, user_id, expires_at}`. HS256 JWT, valid 24h |
| `POST /shows` `{name, seats[], price_paise, per_user_limit?}` | `X-Admin-Token` header | `201`, the show with every seat `available` |
| `GET /shows/{id}` | none | `200`, per-seat status and `counts`; `available + held + confirmed == total_seats` |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | `Authorization: Bearer <token>` | `201`, the reservation |
| `POST /reservations/{id}/cancel` | `Authorization: Bearer <token>` | `200`, the reservation with `status: "cancelled"` |
| `GET /actuator/health/liveness` | none | `200` while the process is up |
| `GET /actuator/health/readiness` | none | `200`; `503` within about 2s when Postgres is unreachable |
| `GET /actuator/prometheus` | none | Prometheus metrics |

**Reserve rules**

- **Seats:** non-empty and no duplicates. Multi-seat requests are **all-or-nothing**: if any seat is taken, nothing is reserved.
- **Idempotency key:** required, 1–128 characters. Send it as the `Idempotency-Key` header or as `idempotency_key` in the body. If you send both, they must match.
- **Key scope:** keys are per user. A retry with the same key and the same seats returns the original reservation with `201`, even after it was cancelled (then with `status: "cancelled"`). It never books again.
- **Declines are not remembered:** retrying a declined request re-evaluates it.
- **Cancel:** only the owner can cancel. Someone else's reservation returns `404`. Cancelling twice returns `200` both times.

**Outcomes**

| Status | `reason` | When |
|---|---|---|
| 201 | | Reserved, or an idempotent replay of the original reservation |
| 409 | `seat_taken` | A requested seat is already confirmed |
| 409 | `per_user_limit` | The user would hold more than `per_user_limit` seats for this show |
| 409 | `key_reused` | The key was already used with different seats or a different show |
| 400 | `invalid_request` | Malformed JSON, missing or invalid fields, duplicate seats, missing key |
| 404 | `not_found` | Unknown show or seat label, or a reservation that isn't the caller's |
| 401 | | Missing, malformed, expired or wrongly signed token; wrong admin token |
| 403 | | A user token on an admin route |

**Conventions**
- JSON field names are snake_case.
- Money is integer paise.
- Error bodies are `{"reason", "message"}`.
- `GET /shows/{id}` lists seats sorted as strings, so `A12` comes before `A2`.
- `held` is always 0 because reservations confirm immediately; see [WRITEUP.md](WRITEUP.md#holds-and-expiry).

## Burst test

`./burst.sh` reproduces the on-sale stampede against a running instance and checks the result.
It needs Java 21 and the service already running.

```bash
./burst.sh                                     # http://localhost:8080
ADMIN_TOKEN=<secret> ./burst.sh https://host   # any other instance
```

| Phase | What it does | Must hold |
|---|---|---|
| 1. Hot-seat storm | 500 users reserve seat A12 at the same instant | exactly one 201, the rest `409 seat_taken` |
| 2. Stampede | ~22k reserves on a 1000-seat show: 40% on ten "good" seats, 10% sent twice with the same key; the show's counts are polled throughout | no seat in two reservations; counts always add up |
| 3. Key reuse | 200 earlier keys resent with different seats | all `409 key_reused` |
| 4. Per-user limit | one user fires 10 parallel reserves on a limit-4 show | exactly 4 succeed |
| 5. Spoofed identity | body carries another `user_id`; then tries to cancel another user's reservation | token user wins; cancel gets 404 |

At the end it prints the outcome distribution and checks three sources against each other:
- what clients received
- what `GET /shows/{id}` reports
- how much each Prometheus counter moved

It exits `1` on any violation, including any 5xx.

| Variable | Default | |
|---|---|---|
| `ADMIN_TOKEN` | `dev-admin-token` | Needed to create the test shows |
| `USERS` | `2000` | Distinct users (tokens minted up front) |
| `REQUESTS` | `20000` | Stampede requests, before the ~10% duplicates |
| `CONCURRENCY` | `500` | Requests in flight at once |
| `HOT_STORM` | `500` | Users in the hot-seat storm |

**Notes**
- Each run creates three new shows and new users, and leaves them in place.
- The metrics comparison assumes nothing else is hitting the service during the run.

Sample run on a laptop (local Docker). Throughput varies between runs, roughly 1.3k–3k req/s;
the correctness checks pass every time.

```
phase 1  hot-seat storm     500 requests    0.6s  {201=1, 409 seat_taken=499}
phase 2  stampede         21976 requests    7.2s  3055 req/s  p50 144 ms  p99 545 ms
                                                   {201=907, 409 per_user_limit=271, 409 seat_taken=20798}
phase 3  key reuse          200 requests          {409 key_reused=200}
phase 4  per-user limit      10 requests          {201=4, 409 per_user_limit=6}
phase 5  spoofed identity  body user_id ignored: true, foreign cancel -> 404

reconciliation (main show)
  API counts            available=0 held=0 confirmed=1000 total=1000  (sum ok: true)
  invariant checks      26 during stampede, all held: true
  seats sold            1000 (by distinct 201s), double-sold: 0
  201s                  908 = 779 new + 129 idempotent replays
  seats gauge           available=0 held=0 confirmed=1000

metrics delta vs client tallies (assumes no other traffic during the run)
  reservations_confirmed_total                               metrics    785  client    785  ok
  reservations_declined_total{reason="idempotent_replay"}    metrics    129  client    129  ok
  reservations_declined_total{reason="key_reused"}           metrics    200  client    200  ok
  reservations_declined_total{reason="per_user_limit"}       metrics    277  client    277  ok
  reservations_declined_total{reason="seat_taken"}           metrics  21297  client  21297  ok

PASS: no double-sells, zero 5xx, invariant held, idempotency and limits held
```

The 1000-seat hall sells out within seconds, so most stampede requests end as `seat_taken`. That's
expected for an on-sale.

## Tests

```bash
./mvnw test
```

There are 50 tests. They run against a real Postgres started by Testcontainers, so Docker must be
running. The concurrency tests cover:
- 500 racers on one seat
- mirrored multi-seat requests (deadlock check)
- overlapping multi-seat requests
- concurrent idempotent retries
- parallel requests against the per-user limit
- cancels racing reserves

## Observability

**Metrics** at `/actuator/prometheus`:

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | New reservations, counted after commit |
| `reservations_cancelled_total` | Cancellations, counted after commit |
| `reservations_declined_total{reason}` | `seat_taken`, `per_user_limit`, `key_reused`, `idempotent_replay` (and `not_found`, `invalid_request` when raised inside the reserve transaction) |
| `seats{show,status}` | Seats per show and status, read from the database at scrape time with one grouped query (cached up to 1s), so it matches `GET /shows/{id}` |
| `http_server_requests_seconds` | Request count and latency by route and status (Spring built-in) |
| `hikaricp_connections_active` / `_pending` | Connection pool use; `pending` rising means requests are queueing for the database |

**Logs** are JSON lines (ECS format) on stdout. Every line of a request carries `request_id`:
your `X-Request-ID` header if it matches `[A-Za-z0-9._-]{1,64}`, otherwise a generated UUID.
The ID is echoed back in the response's `X-Request-ID`. Each request writes:
- one access line: method, path, status, `duration_ms`
- for reserve and cancel, one outcome line: `user_id`, `show_id`, `seats`, `outcome`, `reservation_id`

```bash
docker compose logs -f app                                     # follow live
docker compose logs app | grep '"request_id":"<id>"'           # one request, end to end
docker compose logs -f app | jq -c '{t:."@timestamp", id:.request_id, msg:.message}'   # compact
```

### Dashboard

An optional Prometheus + Grafana stack with a ready-made dashboard. It sits behind a compose
profile, so the default `docker compose up` stays just the app and Postgres.

```bash
docker compose --profile monitoring up -d --build
# open http://localhost:3000 — the dashboard is the home page; no login needed to view
./burst.sh                          # watch it fill in
docker compose --profile monitoring down
```

Prometheus scrapes the app every 2s (UI on `localhost:9090`). The dashboard shows:
- **Stat tiles:** confirmed, each decline reason, and 5xx since start (must stay 0)
- **Outcomes per second:** confirmed and each decline reason
- **Seat counts** of the show being stampeded
- **Reserve latency:** p50, p95, p99
- **HTTP requests per second** by status
- **Connection pool:** active, pending, max
- **Service** up or down

To watch a deployed instance, point the scrape target in `monitoring/prometheus.yml` at its host.

## Configuration

| Variable | Default | |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/seats` | Compose sets it to the `db` service |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `seats` / `seats` | |
| `ADMIN_TOKEN` | `dev-admin-token` | **Set a real secret anywhere but local dev** |
| `JWT_SECRET` | dev value | **Set a real secret (32+ bytes) anywhere but local dev** |
| `DB_POOL_SIZE` | `20` | Hikari pool size; the real cap on concurrent database work |

The schema is applied automatically on startup by Flyway (`src/main/resources/db/migration`).

## Project layout

```
src/main/java/com/example/seats/
  controller/   HTTP endpoints; read the user from the JWT
  service/      transactions: reserve, cancel, show creation, tokens; metrics and outcome logs
  repository/   all SQL (row locks, ON CONFLICT, conditional updates)
  model/        records for rows, requests and responses
  exception/    domain declines and the error-to-HTTP mapping
  config/       security, JWT, request-id filter, readiness check
src/main/resources/db/migration/   Flyway schema
src/test/java/                     integration and concurrency tests
burst/Burst.java, burst.sh         load test
monitoring/                        optional Prometheus + Grafana (compose profile "monitoring")
```

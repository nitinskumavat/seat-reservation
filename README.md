# Seat Reservation

A JSON API that sells assigned seats for a show and stays correct under an on-sale stampede:
no seat is sold twice, no user exceeds their per-show limit, and a retried request never
reserves twice.

Java 21, Spring Boot 4.1, Postgres 17, plain SQL via `JdbcTemplate`.

- Design and reasoning: [DESIGN.md](DESIGN.md)
- Write-up: [WRITEUP.md](WRITEUP.md)
- Live URL: not deployed yet

## Run locally

Requires Docker. Java 21 is only needed for running the tests and the burst script.

```bash
docker compose up --build
```

The API is on `http://localhost:8080`, Postgres on host port `5433`.

## Try it

```bash
# Admin creates a show (local admin token: dev-admin-token)
curl -s -X POST localhost:8080/shows \
  -H 'X-Admin-Token: dev-admin-token' -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A12"],"price_paise":25000}'

# Get a user token (demo auth: any user id; the user is created on first use)
curl -s -X POST localhost:8080/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'

# Reserve (identity comes from the token; the key can also go in an Idempotency-Key header)
curl -s -X POST localhost:8080/shows/$SHOW_ID/reserve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"seats":["A12"],"idempotency_key":"k1"}'

# Show state with per-seat status and counts
curl -s localhost:8080/shows/$SHOW_ID

# Cancel (owner only)
curl -s -X POST localhost:8080/reservations/$RESERVATION_ID/cancel -H "Authorization: Bearer $TOKEN"
```

### Endpoints

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /auth/token` | none | `{"user_id"}` → `{token, user_id, expires_at}` (HS256, 24h) |
| `POST /shows` | `X-Admin-Token` | `{name, seats[], price_paise, per_user_limit?}` (default limit 4) → 201 |
| `GET /shows/{id}` | none | Per-seat status and `counts`; `available + held + confirmed == total_seats` |
| `POST /shows/{id}/reserve` | Bearer | `{seats[], idempotency_key}` → 201 reservation |
| `POST /reservations/{id}/cancel` | Bearer | Owner only; idempotent → 200 |
| `GET /actuator/health/liveness` | none | Process is up |
| `GET /actuator/health/readiness` | none | 503 within ~2s if Postgres is unreachable |
| `GET /actuator/prometheus` | none | Metrics |

### Outcomes

| Status | `reason` | When |
|---|---|---|
| 201 | | Reserved, or an idempotent replay of the original reservation |
| 409 | `seat_taken` | Any requested seat is not available (all-or-nothing: nothing is reserved) |
| 409 | `per_user_limit` | The user would hold more than `per_user_limit` seats for the show |
| 409 | `key_reused` | Same idempotency key with different seats or show |
| 400 | `invalid_request` | Malformed body, duplicate seats, missing key |
| 404 | `not_found` | Unknown show or seat, or a reservation that is not the caller's |
| 401 / 403 | | Missing or invalid token / not admin |

## Burst test

Reproduces the on-sale stampede against any running instance and reconciles the result:

```bash
./burst.sh                       # http://localhost:8080
./burst.sh https://your-host     # remote; set ADMIN_TOKEN if it is not the dev default
```

It runs a hot-seat storm (500 users on one seat), a 20k-request stampede with duplicate retries,
key reuse, a per-user limit race and a spoofed-identity check. Then it prints the outcome
distribution and compares client tallies with `GET /shows` and the Prometheus counters. It exits
non-zero on any violation. Tunable with `USERS`, `REQUESTS`, `CONCURRENCY`, `HOT_STORM`.

Sample local run:

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

PASS: no double-sells, zero 5xx, invariant held, idempotency and limits held
```

## Tests

```bash
./mvnw test
```

Runs against a real Postgres via Testcontainers (Docker required). Concurrency tests cover
500 racers on one seat, mirrored multi-seat requests, overlapping requests, concurrent
idempotent retries, parallel requests against the per-user limit, and cancels racing reserves.

## Observability

- **Metrics** (`/actuator/prometheus`): `reservations_confirmed_total`,
  `reservations_cancelled_total`, `reservations_declined_total{reason}` (including
  `idempotent_replay`), `seats{show,status}` read from the database at scrape time, plus
  Spring's `http_server_requests_seconds` and Hikari pool metrics.
- **Logs**: JSON (ECS) on stdout. Every line of a request carries `request_id` (from
  `X-Request-ID` if supplied, else generated, echoed in the response). Each reservation
  outcome is logged with user, show, seats and outcome:
  `docker compose logs -f app`

## Configuration

| Variable | Default | |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/seats` | Set by compose to the `db` service |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `seats` / `seats` | |
| `ADMIN_TOKEN` | `dev-admin-token` | **Set a real secret outside local dev** |
| `JWT_SECRET` | dev value | **Set a real secret (32+ bytes) outside local dev** |
| `DB_POOL_SIZE` | `20` | Hikari pool size |

# Seat Reservation — Design

Status: implemented and tested locally; deployment pending.
This doc is the exact mechanics. [README.md](README.md) covers running and using the service (endpoints, status codes,
metrics, logs); [WRITEUP.md](WRITEUP.md) covers the reasoning and trade-offs.

## Decisions

| Topic | Decision |
|---|---|
| Stack | Java 21, Spring Boot 4.1.1, Maven wrapper |
| Datastore | Single Postgres; plain SQL via `JdbcTemplate` (no JPA — locking must be visible); Flyway migrations |
| Multi-seat requests | **All-or-nothing**: any requested seat unavailable → whole request 409 |
| Release model | **Confirm on reserve + explicit cancel** (`POST /reservations/{id}/cancel`, owner only). No timed holds; `held` exists in the schema for a later extension, so `held` count is always 0 today |
| Auth | HS256 JWT, `sub` = user id; demo `POST /auth/token` issues one for any user id. Admin uses an `X-Admin-Token` header, kept apart from `Authorization: Bearer`, which the JWT filter owns. A body `user_id` is ignored |
| Concurrency | Virtual threads (`spring.threads.virtual.enabled=true`); Hikari pool is the real bound |
| Money | `bigint` paise, never floating point |
| API conventions | snake_case JSON; errors are `{reason, message}`; seats listed in string order (`A12` before `A2`) |

## Schema

```sql
CREATE TYPE seat_status        AS ENUM ('available', 'held', 'confirmed');
CREATE TYPE reservation_status AS ENUM ('confirmed', 'cancelled');

CREATE TABLE users (
  id         text PRIMARY KEY,               -- JWT sub
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shows (
  id             uuid PRIMARY KEY,
  name           text NOT NULL,
  price_paise    bigint NOT NULL CHECK (price_paise >= 0),
  per_user_limit int NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
  total_seats    int NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE seats (
  show_id        uuid NOT NULL REFERENCES shows(id),
  label          text NOT NULL,
  status         seat_status NOT NULL DEFAULT 'available',
  reservation_id uuid NULL,
  user_id        text NULL REFERENCES users(id),
  PRIMARY KEY (show_id, label)
);

CREATE TABLE reservations (
  id              uuid PRIMARY KEY,
  show_id         uuid NOT NULL REFERENCES shows(id),
  user_id         text NOT NULL REFERENCES users(id),
  seats           text[] NOT NULL,
  amount_paise    bigint NOT NULL,
  status          reservation_status NOT NULL,
  idempotency_key text NOT NULL,
  request_hash    text NOT NULL,             -- sha256(show_id + sorted seats)
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, idempotency_key)
);

CREATE TABLE user_show_counts (
  user_id    text NOT NULL REFERENCES users(id),
  show_id    uuid NOT NULL REFERENCES shows(id),
  seat_count int NOT NULL CHECK (seat_count >= 0),
  PRIMARY KEY (user_id, show_id)
);
```

## Reserve — `POST /shows/{id}/reserve`

Request: `{ "seats": ["A12"], "idempotency_key": "…" }`. The key may instead go in an `Idempotency-Key` header. It is required and must be 1–128 characters; if both are sent they must match (else 400).

Validation:
- Seats must be non-empty and unique (400).
- The show must exist (404).
- Asking for more seats than `per_user_limit` fails at the counter step (409 `per_user_limit`).
- An unknown seat label is detected while locking (404, rolled back).

One transaction, READ COMMITTED. **Lock order is always: idempotency key → user counter → seats sorted by label.**

```
seats = sorted(request.seats); hash = sha256(show_id + seats)
BEGIN
 0. INSERT INTO users (id) VALUES ($u) ON CONFLICT DO NOTHING
      -- a valid token can outlive a database reset; keeps the foreign keys satisfied
 1. INSERT INTO reservations (...) ON CONFLICT (user_id, idempotency_key) DO NOTHING
    0 rows inserted → load the existing reservation for (user, key)
        same hash      → COMMIT, 201 with the original reservation   (metric: idempotent_replay)
        different hash → ROLLBACK, 409 key_reused
 2. INSERT INTO user_show_counts ... VALUES ($u, $s, 0) ON CONFLICT DO NOTHING;
    UPDATE user_show_counts SET seat_count = seat_count + n
      WHERE user_id=$u AND show_id=$s AND seat_count + n <= per_user_limit
    0 rows → ROLLBACK, 409 per_user_limit
 3. SELECT label, status FROM seats
      WHERE show_id=$s AND label = ANY($seats) ORDER BY label FOR UPDATE
    fewer rows than requested       → ROLLBACK, 404 not_found
    any status ≠ 'available'        → ROLLBACK, 409 seat_taken
 4. UPDATE seats SET status='confirmed', reservation_id=$r, user_id=$u
      WHERE show_id=$s AND label = ANY($seats)
COMMIT → 201
```

Code: `ReservationService.reserve`, with the SQL in `repository/`.

Why each step is race-free: [WRITEUP.md → The atomic decision](WRITEUP.md#the-atomic-decision).

### Idempotency cases

Keys are scoped per user: `UNIQUE (user_id, idempotency_key)`. The hash covers `show_id` + sorted seats.

| # | Case | Outcome |
|---|---|---|
| 1 | New key | Evaluated normally → 201 or 409 |
| 2 | Same key, same body, after success | Conflict, hash matches → 201 with the original reservation; nothing new booked |
| 3 | Same key, different seats | Conflict, hash differs → 409 `key_reused` |
| 4 | Same key, same seats, different show | Hash includes `show_id` → 409 `key_reused` |
| 5 | Same seats, different order | Seats sorted before hashing → 201 replay |
| 6 | Two identical requests concurrently | Second INSERT blocks on the unique index; first commits → second replays. One reservation, both 201 |
| 7 | Same key, different bodies, concurrently | Second blocks; first commits → second gets 409 `key_reused` |
| 8 | Concurrent duplicate where the first fails | First rolls back and its key disappears; second inserts and is evaluated fresh |
| 9 | Retry after a 409 decline | Declines are not stored → re-evaluated (usually 409 again; 201 if the seat was freed by a cancel) |
| 10 | Retry after the reservation was cancelled | Replays the original reservation in its current state (`status: "cancelled"`); the seat is **not** re-booked |
| 11 | Same key from two different users | Different scope → independent requests |
| 12 | Missing key | 400 |

Why cases 8–10 behave this way: [WRITEUP.md → Idempotency](WRITEUP.md#idempotency).

## Cancel — `POST /reservations/{id}/cancel`

Lock order: reservation → user counter → seats by label (the same counter → seats order as reserve).

```
BEGIN
 SELECT * FROM reservations WHERE id=$r AND user_id=$token_user FOR UPDATE
   not found         → 404 (does not reveal other users' reservations)
   already cancelled → 200, cancelled reservation (idempotent)
 UPDATE user_show_counts SET seat_count = seat_count - n WHERE user_id=$u AND show_id=$s
 SELECT ... FROM seats WHERE show_id=$s AND label = ANY($seats) ORDER BY label FOR UPDATE
 UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL
   WHERE show_id=$s AND label = ANY($seats) AND reservation_id=$r
 UPDATE reservations SET status='cancelled' WHERE id=$r
COMMIT → 200
```

- **No stale release:** the `reservation_id = $r` guard means a cancel can never release a seat that is now someone else's.
- **Explicit seat lock:** without it, the release `UPDATE` would lock rows in whatever order the query plan scans them. That could deadlock against a reserve holding the same seats in label order. Today Postgres happens to scan in label order, so the explicit lock guarantees what the plan only does by chance.

## Observability internals

- Counters are incremented after commit, so rolled-back work is never counted. The main decline reasons are created at 0 on startup; a series that first appears already at N looks like no change to Prometheus' `increase()`.
- `seats{show,status}` gauges are served by one grouped query, reused for 100ms within a scrape, so a scrape uses one pool connection however many shows exist.
- Readiness opens its own connection with 2s timeouts instead of borrowing from the pool: it fails fast when Postgres is down and is not starved by a busy pool.

## Risks and what was measured

| Risk | Status |
|---|---|
| **Pool saturation.** Virtual threads remove the Tomcat thread cap, so requests queue on Hikari (20 connections); a wait past 30s becomes a 5xx | Local burst: ~22k requests at 500 in flight (p99 545 ms) and ~33k at 2,000 in flight (p99 1.8 s), zero 5xx; laptop throughput varies between runs (1.3k–3k req/s). **Not yet measured on deployed hardware.** |
| **Virtual-thread pinning on Java 21.** `synchronized` pins carrier threads (fixed in JDK 24) | Not observed under the local burst; current HikariCP and pgjdbc avoid `synchronized` on hot paths |
| **Hot-seat lock queue.** Hundreds of waiters on one row lock | 500- and 1,000-user storms resolve in about 0.6 s with exactly one winner |

## Build history

Built one step per commit (scaffold → schema → auth → reserve → idempotency → limit → cancel → observability → burst → docs), each verified by its own tests before the next; an optional dashboard followed. See `git log`. Remaining: deployment.

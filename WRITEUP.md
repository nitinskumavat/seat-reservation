<!-- DRAFT: review every section, especially "AI usage", and rewrite in your own words before submitting. -->

# Write-up

## The atomic decision

Every reservation is one Postgres transaction at READ COMMITTED. The decision of who gets a seat
is a **row lock taken in a fixed order**:

```sql
SELECT label, status FROM seats
WHERE show_id = ? AND label = ANY(?)
ORDER BY label
FOR UPDATE;
```

If any locked seat is not `available`, the transaction rolls back and the caller gets
`409 seat_taken`. Otherwise all requested seats are set to `confirmed` in the same transaction.

**Why it is race-free.** There is no read-then-write gap. `FOR UPDATE` makes every contender for
a seat wait for the current lock holder. When the lock is released, Postgres re-reads the row's
latest committed version for the waiter (READ COMMITTED re-check). The waiter therefore sees
`confirmed` and declines. With 500 racers on one seat, exactly one commits and 499 get a clean
409. I checked this with a mutation test: removing `FOR UPDATE` makes all three concurrency
tests fail (multiple winners, 18 seats "sold" from 6).

**No deadlocks for multi-seat requests.** Every transaction locks seats in label order, so two
requests for `[A1, A2]` and `[A2, A1]` cannot each hold one seat and wait for the other. Cancel
locks the same rows in the same order before releasing them. Across a transaction, locks are
always taken in this order: idempotency key → the user's per-show counter → seats by label.

**Partial requests are all-or-nothing.** If any requested seat is taken, nothing is reserved.

**Per-user limit.** A counter row per (user, show) is updated conditionally:

```sql
UPDATE user_show_counts SET seat_count = seat_count + :n
WHERE user_id = :u AND show_id = :s AND seat_count + :n <= :limit
```

Zero rows updated means `409 per_user_limit`. The row lock queues one user's parallel requests,
so 10 parallel reserves on a limit-4 show yield exactly 4.

**Identity** comes only from the JWT subject. The request body has no user field. A spoofed
`user_id` is ignored, and cancel looks up reservations by `(id, user_id)`, so someone else's
reservation returns 404.

## Idempotency

**Where the key lives.** It lives in the `reservations` row itself, under
`UNIQUE (user_id, idempotency_key)`, next to a SHA-256 of the request (show id + sorted seats).

**How exactly-once is enforced.** The first statement of the reserve transaction claims the key:

```sql
INSERT INTO reservations (...) VALUES (...)
ON CONFLICT (user_id, idempotency_key) DO NOTHING
```

The key and the seat changes commit or roll back together, so a reservation never exists without
its key, or the reverse.

- If a concurrent duplicate arrives, its INSERT blocks on the unique index until the first
  transaction ends. If the first commits, the duplicate sees the conflict and replays the
  original reservation (201, same body). If the first rolls back (seat taken, for example), the
  duplicate inserts and is evaluated fresh.
- Because there is no external side effect outside the transaction, no `IN_PROGRESS` state or
  recovery job is needed.

**Same key, different body.** The stored hash differs, so the request gets `409 key_reused` and
nothing changes. The hash covers the show, so reusing a key on another show is also rejected.
Seats are sorted before hashing, so `[A2, A1]` replays `[A1, A2]`.

**Deliberate choices.**
- Declines are not stored against the key: a retry after `seat_taken` is re-evaluated and can
  succeed if the seat was freed.
- A replay after cancel returns the reservation as `cancelled` and does not re-book it.
- Keys are scoped per user.

## Holds and expiry

I chose **confirm on reserve plus explicit owner cancel**. The spec has no payment step, and the
transaction's row lock already acts as the "hold" for the few milliseconds the decision takes.
Cancel releases seats with:

```sql
UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL
WHERE show_id = ? AND label = ANY(?) AND reservation_id = :this_reservation
```

The `reservation_id` guard means a stale or repeated cancel can never free a seat that has since
been confirmed to someone else. Cancel is idempotent, and the user's counter is decremented
exactly once.

**If payment were added**, holds would become necessary, because a DB lock cannot be held across
a slow external call. The schema already has a `held` status. The change would be:
1. Reserve writes `held` with an `expires_at`.
2. Confirm is a guarded `UPDATE ... SET status = 'confirmed' WHERE status = 'held' AND expires_at > now()`.
3. Expiry is a guarded `UPDATE ... SET status = 'available' WHERE status = 'held' AND expires_at < now()`.

The guards mean an expiry can never touch a confirmed seat.

## Consistency vs availability under a partition

This service chooses **consistency**. Postgres is the only source of truth. The app keeps no
seat state in memory and never decides locally. If the app cannot reach the database:
- Readiness returns 503 within about 2 seconds (it uses its own short-timeout connection, not
  the pool), so a load balancer stops routing traffic.
- Reserve requests fail rather than guess.

A seat is never sold that the database has not committed. The cost is that sales stop during
the partition. For a ticketing system of record, that is the right trade: an oversold seat is
worse than a few seconds of "try again".

## Observability: what pages me at 2am

Signals available today:
- `reservations_confirmed_total`
- `reservations_cancelled_total`
- `reservations_declined_total{reason}`
- `seats{show,status}` (read from the database at scrape time)
- `http_server_requests_seconds`
- `hikaricp_connections_pending` / `_active`
- JSON logs with `request_id` and a per-reservation outcome line

**Page:**
- **Any 5xx on the reserve route** (`http_server_requests_seconds_count{uri=".../reserve",outcome="SERVER_ERROR"}` rate > 0). Declines are 4xx by design, so a 5xx is always a bug or an outage.
- **Readiness failing** for more than a minute (the database is unreachable).
- **Invariant drift.** For any show, `sum by (show) (seats)` changes from its total. It must be constant.
- **`hikaricp_connections_pending` sustained high together with rising reserve p99.** The pool is the bottleneck, and requests are close to the connection timeout that would turn into 5xx.

**Ticket, not page:**
- Confirmed rate drops to zero during a known on-sale while requests keep arriving.
- Unusual `key_reused` volume (a client bug).
- Unusual `per_user_limit` volume (possible scalping).

## AI usage

I built this with Claude Code (Claude Opus 5.5) as a pair. Every commit carries a
`Co-Authored-By: Claude` trailer.

**What I decided:**
- **Stack:** I first considered Python/FastAPI, then chose Java and Spring Boot, and pinned it to Java 21.
- **Partial requests:** all-or-nothing.
- **Release model:** confirm plus explicit cancel, over time-boxed holds, after asking for the trade-off to be explained.
- **Schema:** adding a `users` table, and using Postgres enums for status instead of text with CHECK constraints.
- **Code layout:** layered controller/service/repository structure.
- **Scope:** running locally first and deferring deployment.
- **Repo hygiene:** which files stay out of the repo, and the commit identity.

**What Claude proposed and I accepted:**
- The core mechanisms: `FOR UPDATE` in label order, the conditional counter update, and claiming the idempotency key with `ON CONFLICT` inside the same transaction.
- The idempotency case table.
- Using raw SQL over JPA so the locking stays visible.
- The demo token endpoint.
- `X-Admin-Token` instead of a bearer admin token, to avoid clashing with the JWT filter.

**What Claude wrote:** all code, tests and the burst script. I reviewed each step, and
`DESIGN.md` was agreed before any code.

**Issues Claude caught during implementation:**
- A port clash with my local Postgres.
- A 30s readiness timeout caused by the probe borrowing from the pool.
- Cancel needing an explicit ordered lock to rule out deadlocks against concurrent reserves.

It also ran mutation checks to confirm the tests actually detect a broken lock. One of them
showed the cancel deadlock test does not catch the missing lock, because Postgres happens to
scan in label order; the lock is kept so the order is guaranteed rather than incidental.

## What I'd do next

1. **Deploy** and run `./burst.sh` against the live URL, sizing the instance and pool from the
   result.
2. **Return 503 with `Retry-After`** instead of a 500 when the pool times out, and add
   load-shedding (a bounded in-flight limit on reserve) so overload degrades into fast,
   retryable responses.
3. **Time-boxed holds and payment**, as described above.
4. **Scale the seat gauges.** They run one query per show and status per scrape and are
   registered for every show ever created. For many shows, switch to one grouped query per
   scrape and only track shows that are on sale.
5. **Real identity:** replace the demo token endpoint with an OIDC provider. Nothing else
   changes, because the service only reads `sub`.
6. **Natural seat ordering** in `GET /shows/{id}` (`A2` before `A12`). This is display only; the
   lock order can stay lexicographic.

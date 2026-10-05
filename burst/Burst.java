import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-sale stampede against a running seat-reservation service. Prints the outcome
 * distribution and a reconciliation of client tallies, API state and Prometheus metrics.
 * Exits non-zero if any correctness check fails.
 *
 * Usage: java burst/Burst.java <BASE_URL>
 * Env:   ADMIN_TOKEN (dev-admin-token), USERS (2000), REQUESTS (20000), CONCURRENCY (500),
 *        HOT_STORM (500)
 */
public class Burst {

	static String base;
	static final HttpClient http = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.executor(Executors.newVirtualThreadPerTaskExecutor())
		.build();
	static final List<String> violations = Collections.synchronizedList(new ArrayList<>());

	record Res(int status, String body, long micros) {
	}

	/** One 201 from a reserve call. */
	record Created(String reservationId, String userId, List<String> seats, String key) {
	}

	/** Outcome tallies plus every 201 seen, for one show. */
	static final class Tally {
		final Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
		final Queue201 created = new Queue201();
		final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

		void count(String outcome) {
			outcomes.computeIfAbsent(outcome, k -> new LongAdder()).increment();
		}

		long get(String outcome) {
			LongAdder a = outcomes.get(outcome);
			return a == null ? 0 : a.sum();
		}
	}

	static final class Queue201 extends ConcurrentLinkedQueue<Created> {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("usage: java burst/Burst.java <BASE_URL>");
			System.exit(2);
		}
		base = args[0].replaceAll("/+$", "");
		String adminToken = env("ADMIN_TOKEN", "dev-admin-token");
		int users = Integer.parseInt(env("USERS", "2000"));
		int requests = Integer.parseInt(env("REQUESTS", "20000"));
		int concurrency = Integer.parseInt(env("CONCURRENCY", "500"));
		int hotStorm = Math.min(Integer.parseInt(env("HOT_STORM", "500")), users);
		String run = Long.toString(System.currentTimeMillis(), 36);

		System.out.printf("Burst against %s  (users=%d requests=%d concurrency=%d hot_storm=%d)%n%n", base, users,
				requests, concurrency, hotStorm);

		Res ready = send("GET", "/actuator/health/readiness", null, Map.of());
		if (ready.status() != 200) {
			System.err.println("service not ready: " + ready.status() + " " + ready.body());
			System.exit(1);
		}

		// Hall: rows A-T x seats 1-50 = 1000 seats. A1-A10 are the "good" seats everyone wants.
		List<String> hall = new ArrayList<>();
		for (char row = 'A'; row <= 'T'; row++) {
			for (int n = 1; n <= 50; n++) {
				hall.add("" + row + n);
			}
		}
		List<String> hot = hall.subList(0, 10);
		String show = createShow(adminToken, "burst-" + run, hall, 4);

		long t0 = System.nanoTime();
		String[] tokens = new String[users];
		parallel(users, concurrency, i -> tokens[i] = mintToken("burst-" + run + "-" + i));
		System.out.printf("setup: show %s (%d seats), %d tokens minted in %.1fs%n%n", show, hall.size(), users,
				(System.nanoTime() - t0) / 1e9);

		Map<String, Double> metricsBefore = scrapeMetrics();

		// Phase 1: hot-seat storm. Many users, one seat, released at the same instant.
		Tally storm = new Tally();
		long p1 = System.nanoTime();
		parallel(hotStorm, concurrency, i -> reserve(storm, tokens[i], show, List.of("A12"), "hot-" + run));
		double p1s = (System.nanoTime() - p1) / 1e9;

		// Phase 2: stampede. Random users; 40% fight over A1-A10; 10% of requests are sent twice
		// at once with the same key (client retries).
		Random rnd = new Random(42);
		List<Runnable> stampede = new ArrayList<>();
		Tally main = new Tally();
		for (int i = 0; i < requests; i++) {
			int u = rnd.nextInt(users);
			List<String> seats;
			if (rnd.nextDouble() < 0.4) {
				seats = List.of(hot.get(rnd.nextInt(hot.size())));
			}
			else {
				String a = hall.get(rnd.nextInt(hall.size()));
				String b = hall.get(rnd.nextInt(hall.size()));
				seats = rnd.nextBoolean() || a.equals(b) ? List.of(a) : List.of(a, b);
			}
			String key = "s-" + run + "-" + i;
			int copies = rnd.nextDouble() < 0.10 ? 2 : 1;
			for (int c = 0; c < copies; c++) {
				stampede.add(() -> reserve(main, tokens[u], show, seats, key));
			}
		}
		Collections.shuffle(stampede, rnd);
		AtomicBoolean running = new AtomicBoolean(true);
		AtomicInteger invariantChecks = new AtomicInteger();
		Thread poller = Thread.ofVirtual().start(() -> {
			while (running.get()) {
				checkInvariant(show, "during stampede");
				invariantChecks.incrementAndGet();
				sleep(100);
			}
		});
		long p2 = System.nanoTime();
		parallel(stampede.size(), concurrency, i -> stampede.get(i).run());
		double p2s = (System.nanoTime() - p2) / 1e9;
		running.set(false);
		poller.join();

		// Phase 3: same key, different seats, for up to 200 of the reservations just made.
		Tally reuse = new Tally();
		List<Created> sample = new ArrayList<>(main.created).subList(0, Math.min(200, main.created.size()));
		parallel(sample.size(), concurrency, i -> {
			Created c = sample.get(i);
			List<String> other = List.of(c.seats().contains("T50") ? "T49" : "T50");
			reserve(reuse, tokenFor(tokens, run, c.userId()), show, other, c.key());
		});

		// Phase 4: one user fires 10 parallel reserves on a limit-4 show.
		List<String> ten = new ArrayList<>();
		for (int n = 1; n <= 10; n++) {
			ten.add("L" + n);
		}
		String limitShow = createShow(adminToken, "burst-limit-" + run, ten, 4);
		String limitUser = mintToken("burst-limit-" + run);
		Tally limit = new Tally();
		parallel(10, 10, i -> reserve(limit, limitUser, limitShow, List.of(ten.get(i)), "l-" + i));

		// Phase 5: identity comes from the token, not the body.
		String spoofShow = createShow(adminToken, "burst-spoof-" + run, List.of("S1", "S2"), 4);
		String victim = "burst-" + run + "-0";
		Tally spoof = new Tally();
		reserve(spoof, tokens[0], spoofShow, List.of("S1"), "v-" + run);
		String attacker = "burst-attacker-" + run;
		String attackerToken = mintToken(attacker);
		Res spoofed = send("POST", "/shows/" + spoofShow + "/reserve",
				"{\"seats\":[\"S2\"],\"idempotency_key\":\"a-" + run + "\",\"user_id\":\"" + victim + "\"}",
				Map.of("Authorization", "Bearer " + attackerToken));
		spoof.count(outcome(spoofed));
		if (spoofed.status() == 201) {
			spoof.created.add(parseCreated(spoofed.body(), "a-" + run));
		}
		boolean spoofIgnored = spoofed.status() == 201 && attacker.equals(str(spoofed.body(), "user_id"));
		String victimReservation = spoof.created.stream().filter(c -> c.userId().equals(victim)).findFirst()
			.map(Created::reservationId).orElse("missing");
		Res foreignCancel = send("POST", "/reservations/" + victimReservation + "/cancel", null,
				Map.of("Authorization", "Bearer " + attackerToken));
		check(spoofIgnored, "spoofed body user_id was not ignored: " + spoofed.status() + " " + spoofed.body());
		check(foreignCancel.status() == 404,
				"cancelling another user's reservation returned " + foreignCancel.status() + ", expected 404");
		check(confirmed(spoofShow) == 2, "victim's seat was released by the attacker's cancel");

		// Final reconciliation.
		Map<String, Double> metricsAfter = scrapeMetrics();
		Map<String, Long> showState = checkInvariant(show, "after burst");

		List<Tally> all = List.of(storm, main, reuse, limit, spoof);
		Map<String, Set<String>> owners = new HashMap<>();
		Set<String> distinct = new HashSet<>();
		long created201 = 0;
		for (Tally t : List.of(storm, main)) {
			for (Created c : t.created) {
				created201++;
				if (distinct.add(c.reservationId())) {
					for (String seat : c.seats()) {
						owners.computeIfAbsent(seat, k -> new HashSet<>()).add(c.reservationId());
					}
				}
			}
		}
		long doubleSold = owners.values().stream().filter(s -> s.size() > 1).count();
		long seatsSold = owners.size();
		long hotWinners = storm.created.stream().map(Created::reservationId).distinct().count();
		long replays = created201 - distinct.size();
		long newReservations = distinct.size() + limit.get("201") + spoof.created.size();

		check(doubleSold == 0, doubleSold + " seat(s) confirmed to more than one reservation");
		check(hotWinners == 1, "hot seat A12 had " + hotWinners + " winners, expected exactly 1");
		check(storm.get("201") == 1 && storm.get("409 seat_taken") == hotStorm - 1,
				"hot storm outcomes " + storm.outcomes + ", expected 1x201 and " + (hotStorm - 1) + "x409 seat_taken");
		check(showState.get("confirmed") == seatsSold, "API reports " + showState.get("confirmed")
				+ " confirmed seats but clients received 201s for " + seatsSold);
		check(reuse.get("409 key_reused") == sample.size(),
				"same key with different seats: " + reuse.outcomes + ", expected all 409 key_reused");
		check(limit.get("201") == 4 && limit.get("409 per_user_limit") == 6,
				"limit test outcomes " + limit.outcomes + ", expected 4x201 and 6x409 per_user_limit");
		long fiveXX = all.stream().mapToLong(t -> t.outcomes.entrySet().stream()
			.filter(e -> e.getKey().startsWith("5") || e.getKey().startsWith("transport"))
			.mapToLong(e -> e.getValue().sum()).sum()).sum();
		check(fiveXX == 0, fiveXX + " server errors or transport failures");

		Map<String, Long> expectedMetrics = new TreeMap<>();
		expectedMetrics.put("reservations_confirmed_total", newReservations);
		expectedMetrics.put("reservations_declined_total{reason=\"idempotent_replay\"}", replays);
		for (String reason : List.of("seat_taken", "per_user_limit", "key_reused")) {
			expectedMetrics.put("reservations_declined_total{reason=\"" + reason + "\"}",
					all.stream().mapToLong(t -> t.get("409 " + reason)).sum());
		}
		Map<String, Double> gauges = Map.of(
				"available", metricsAfter.getOrDefault(seatGauge(show, "available"), -1.0),
				"held", metricsAfter.getOrDefault(seatGauge(show, "held"), -1.0),
				"confirmed", metricsAfter.getOrDefault(seatGauge(show, "confirmed"), -1.0));

		// Report.
		System.out.printf("phase 1  hot-seat storm   %5d requests  %5.1fs  %s%n", hotStorm, p1s, sorted(storm));
		System.out.printf("phase 2  stampede         %5d requests  %5.1fs  %.0f req/s  p50 %d ms  p99 %d ms%n",
				stampede.size(), p2s, stampede.size() / p2s, pct(main, 50), pct(main, 99));
		System.out.printf("                                                   %s%n", sorted(main));
		System.out.printf("phase 3  key reuse        %5d requests          %s%n", sample.size(), sorted(reuse));
		System.out.printf("phase 4  per-user limit   %5d requests          %s%n", 10, sorted(limit));
		System.out.printf("phase 5  spoofed identity  body user_id ignored: %s, foreign cancel -> %d%n%n",
				spoofIgnored, foreignCancel.status());

		System.out.println("reconciliation (main show)");
		System.out.printf("  API counts            available=%d held=%d confirmed=%d total=%d  (sum ok: %s)%n",
				showState.get("available"), showState.get("held"), showState.get("confirmed"),
				showState.get("total"), showState.get("available") + showState.get("held")
						+ showState.get("confirmed") == showState.get("total"));
		System.out.printf("  invariant checks      %d during stampede, all held: %s%n", invariantChecks.get(),
				violations.stream().noneMatch(v -> v.contains("during stampede")));
		System.out.printf("  seats sold            %d (by distinct 201s), double-sold: %d%n", seatsSold, doubleSold);
		System.out.printf("  201s                  %d = %d new + %d idempotent replays%n", created201, distinct.size(),
				replays);
		System.out.printf("  seats gauge           available=%.0f held=%.0f confirmed=%.0f%n", gauges.get("available"),
				gauges.get("held"), gauges.get("confirmed"));
		check(gauges.get("available").longValue() == showState.get("available")
				&& gauges.get("held").longValue() == showState.get("held")
				&& gauges.get("confirmed").longValue() == showState.get("confirmed"),
				"seats gauge does not match API counts");

		System.out.println("\nmetrics delta vs client tallies (assumes no other traffic during the run)");
		expectedMetrics.forEach((name, expected) -> {
			double delta = metricsAfter.getOrDefault(name, 0.0) - metricsBefore.getOrDefault(name, 0.0);
			boolean ok = delta == expected;
			System.out.printf("  %-58s metrics %6.0f  client %6d  %s%n", name, delta, expected, ok ? "ok" : "MISMATCH");
			check(ok, "metric " + name + " moved by " + delta + ", clients observed " + expected);
		});

		System.out.println();
		if (violations.isEmpty()) {
			System.out.println("PASS: no double-sells, zero 5xx, invariant held, idempotency and limits held");
		}
		else {
			System.out.println("FAIL:");
			new HashSet<>(violations).forEach(v -> System.out.println("  - " + v));
			System.exit(1);
		}
	}

	static void reserve(Tally t, String token, String show, List<String> seats, String key) {
		String body = "{\"seats\":[" + String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList())
				+ "],\"idempotency_key\":\"" + key + "\"}";
		Res r = send("POST", "/shows/" + show + "/reserve", body, Map.of("Authorization", "Bearer " + token));
		t.latencies.add(r.micros());
		t.count(outcome(r));
		if (r.status() == 201) {
			t.created.add(parseCreated(r.body(), key));
		}
	}

	static String outcome(Res r) {
		if (r.status() < 0) {
			return "transport_error";
		}
		String reason = str(r.body(), "reason");
		return reason == null ? Integer.toString(r.status()) : r.status() + " " + reason;
	}

	static Created parseCreated(String body, String key) {
		Matcher m = Pattern.compile("\"seats\":\\[(.*?)]").matcher(body);
		List<String> seats = m.find() ? Arrays.stream(m.group(1).split(",")).map(s -> s.replace("\"", "")).toList()
				: List.of();
		return new Created(str(body, "reservation_id"), str(body, "user_id"), seats, key);
	}

	static Map<String, Long> checkInvariant(String show, String when) {
		Res r = send("GET", "/shows/" + show, null, Map.of());
		if (r.status() != 200) {
			check(false, "GET /shows " + when + " returned " + r.status());
			return Map.of("available", -1L, "held", -1L, "confirmed", -1L, "total", -1L);
		}
		Map<String, Long> s = Map.of("available", num(r.body(), "available"), "held", num(r.body(), "held"),
				"confirmed", num(r.body(), "confirmed"), "total", num(r.body(), "total_seats"));
		check(s.get("available") + s.get("held") + s.get("confirmed") == s.get("total"),
				"invariant broken " + when + ": " + s);
		return s;
	}

	static long confirmed(String show) {
		return num(send("GET", "/shows/" + show, null, Map.of()).body(), "confirmed");
	}

	static String createShow(String adminToken, String name, List<String> seats, int limit) {
		String body = "{\"name\":\"" + name + "\",\"price_paise\":25000,\"per_user_limit\":" + limit + ",\"seats\":["
				+ String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList()) + "]}";
		Res r = send("POST", "/shows", body, Map.of("X-Admin-Token", adminToken));
		if (r.status() != 201) {
			System.err.println("could not create show: " + r.status() + " " + r.body());
			System.exit(1);
		}
		return str(r.body(), "id");
	}

	static String mintToken(String user) {
		Res r = send("POST", "/auth/token", "{\"user_id\":\"" + user + "\"}", Map.of());
		if (r.status() != 200) {
			throw new IllegalStateException("token for " + user + ": " + r.status() + " " + r.body());
		}
		return str(r.body(), "token");
	}

	static String tokenFor(String[] tokens, String run, String userId) {
		return tokens[Integer.parseInt(userId.substring(("burst-" + run + "-").length()))];
	}

	static Map<String, Double> scrapeMetrics() {
		Map<String, Double> m = new HashMap<>();
		for (String line : send("GET", "/actuator/prometheus", null, Map.of()).body().split("\n")) {
			if (line.startsWith("reservations_") || line.startsWith("seats{")) {
				int sp = line.lastIndexOf(' ');
				m.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
			}
		}
		return m;
	}

	static String seatGauge(String show, String status) {
		return "seats{show=\"" + show + "\",status=\"" + status + "\"}";
	}

	static Res send(String method, String path, String body, Map<String, String> headers) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
		headers.forEach(b::header);
		if (body != null) {
			b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
		}
		else {
			b.method(method, BodyPublishers.noBody());
		}
		long start = System.nanoTime();
		try {
			var r = http.send(b.build(), BodyHandlers.ofString());
			return new Res(r.statusCode(), r.body(), (System.nanoTime() - start) / 1000);
		}
		catch (Exception e) {
			return new Res(-1, e.toString(), (System.nanoTime() - start) / 1000);
		}
	}

	interface IntTask {
		void run(int i) throws Exception;
	}

	/** Runs n tasks on virtual threads, at most `concurrency` in flight, all released together. */
	static void parallel(int n, int concurrency, IntTask task) throws Exception {
		Semaphore permits = new Semaphore(concurrency);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				int idx = i;
				pool.submit(() -> {
					start.await();
					permits.acquire();
					try {
						task.run(idx);
					}
					finally {
						permits.release();
					}
					return null;
				});
			}
			start.countDown();
		}
	}

	static void check(boolean ok, String violation) {
		if (!ok) {
			violations.add(violation);
		}
	}

	static String str(String json, String field) {
		Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	static long num(String json, String field) {
		Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	static long pct(Tally t, int p) {
		List<Long> l = new ArrayList<>(t.latencies);
		Collections.sort(l);
		return l.isEmpty() ? 0 : l.get(Math.min(l.size() - 1, l.size() * p / 100)) / 1000;
	}

	static String sorted(Tally t) {
		return new TreeMap<>(t.outcomes).toString();
	}

	static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v;
	}

	static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

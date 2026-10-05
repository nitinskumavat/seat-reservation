package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import com.example.seats.exception.ApiException;
import com.example.seats.model.ReservationResponse;
import com.example.seats.model.ShowResponse.Counts;
import com.example.seats.service.ReservationService;

import io.micrometer.core.instrument.MeterRegistry;

class ObservabilityTest extends ApiTestSupport {

	@Autowired
	ReservationService reservations;

	@Autowired
	MeterRegistry registry;

	@Autowired
	@Qualifier("dbConnectivityHealthIndicator")
	HealthIndicator dbConnectivity;

	@Test
	void countersTrackCommittedOutcomesAndGaugesMatchTheApi() {
		Map<String, Double> before = snapshot();
		UUID show = createShow(2, "A1", "A2", "A3");
		String alice = unique("alice");

		ReservationResponse r = reservations.reserve(alice, show, List.of("A1"), "k1"); // confirmed
		reservations.reserve(alice, show, List.of("A1"), "k1"); // idempotent_replay
		ignoreDecline(() -> reservations.reserve(alice, show, List.of("A2"), "k1")); // key_reused
		ignoreDecline(() -> reservations.reserve(unique("bob"), show, List.of("A1"), "k")); // seat_taken
		ignoreDecline(() -> reservations.reserve(alice, show, List.of("A2", "A3"), "k2")); // per_user_limit
		reservations.cancel(alice, r.reservationId()); // cancelled
		reservations.reserve(unique("carol"), show, List.of("A1", "A2"), "k"); // confirmed

		Map<String, Double> after = snapshot();
		assertThat(delta(before, after, "confirmed")).isEqualTo(2);
		assertThat(delta(before, after, "cancelled")).isEqualTo(1);
		assertThat(delta(before, after, "idempotent_replay")).isEqualTo(1);
		assertThat(delta(before, after, "key_reused")).isEqualTo(1);
		assertThat(delta(before, after, "seat_taken")).isEqualTo(1);
		assertThat(delta(before, after, "per_user_limit")).isEqualTo(1);

		Counts counts = counts(show);
		assertThat(counts).isEqualTo(new Counts(1, 0, 2));
		assertThat(seatGauge(show, "available")).isEqualTo(counts.available());
		assertThat(seatGauge(show, "held")).isEqualTo(counts.held());
		assertThat(seatGauge(show, "confirmed")).isEqualTo(counts.confirmed());
	}

	@Test
	void requestIdIsEchoedOrGenerated() throws Exception {
		mvc.perform(get("/actuator/health/liveness").header("X-Request-ID", "abc-123"))
			.andExpect(header().string("X-Request-ID", "abc-123"));
		mvc.perform(get("/actuator/health/liveness"))
			.andExpect(header().string("X-Request-ID", matchesPattern("[0-9a-f-]{36}")));
		mvc.perform(get("/actuator/health/liveness").header("X-Request-ID", "bad\nvalue"))
			.andExpect(header().string("X-Request-ID", matchesPattern("[0-9a-f-]{36}")));
	}

	@Test
	void readinessChecksTheDatabase() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
		assertThat(dbConnectivity.health().getStatus()).isEqualTo(Status.UP);
	}

	private Map<String, Double> snapshot() {
		return Map.of(
				"confirmed", registry.get("reservations.confirmed").counter().count(),
				"cancelled", registry.get("reservations.cancelled").counter().count(),
				"idempotent_replay", declined("idempotent_replay"),
				"key_reused", declined("key_reused"),
				"seat_taken", declined("seat_taken"),
				"per_user_limit", declined("per_user_limit"));
	}

	private double declined(String reason) {
		var counter = registry.find("reservations.declined").tag("reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

	private static double delta(Map<String, Double> before, Map<String, Double> after, String key) {
		return after.get(key) - before.get(key);
	}

	private double seatGauge(UUID show, String status) {
		return registry.get("seats").tag("show", show.toString()).tag("status", status).gauge().value();
	}

	private static void ignoreDecline(Runnable r) {
		try {
			r.run();
		}
		catch (ApiException expected) {
		}
	}
}

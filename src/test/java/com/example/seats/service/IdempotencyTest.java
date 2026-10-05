package com.example.seats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.seats.ApiTestSupport;
import com.example.seats.exception.ApiException;
import com.example.seats.model.ReservationResponse;
import com.example.seats.model.ShowResponse.Counts;

/** Cases refer to the idempotency table in DESIGN.md. */
class IdempotencyTest extends ApiTestSupport {

	@Autowired
	ReservationService reservations;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void retryReturnsTheOriginalReservation() throws Exception { // case 2
		UUID show = createShow(4, "A1", "A2");
		String t = token(unique("alice"));
		String body = "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}";

		String first = reserve(t, show, body).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		reserve(t, show, body).andExpect(status().isCreated())
			.andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(first));

		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 1));
	}

	@Test
	void sameKeyDifferentSeatsIsRejected() throws Exception { // case 3
		UUID show = createShow(4, "A1", "A2");
		String t = token(unique("alice"));
		reserve(t, show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}").andExpect(status().isCreated());

		reserve(t, show, "{\"seats\":[\"A2\"],\"idempotency_key\":\"k1\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("key_reused"));
		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 1));
	}

	@Test
	void sameKeyOnAnotherShowIsRejected() throws Exception { // case 4
		UUID show1 = createShow(4, "A1");
		UUID show2 = createShow(4, "A1");
		String t = token(unique("alice"));
		reserve(t, show1, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}").andExpect(status().isCreated());

		reserve(t, show2, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("key_reused"));
		assertThat(counts(show2)).isEqualTo(new Counts(1, 0, 0));
	}

	@Test
	void seatOrderDoesNotMatter() { // case 5
		UUID show = createShow(4, "A1", "A2");
		String alice = unique("alice");
		ReservationResponse first = reservations.reserve(alice, show, List.of("A1", "A2"), "k1");
		ReservationResponse retry = reservations.reserve(alice, show, List.of("A2", "A1"), "k1");
		assertThat(retry).isEqualTo(first);
	}

	@Test
	void concurrentDuplicatesProduceOneReservation() throws Exception { // case 6
		UUID show = createShow(4, "A1");
		String alice = unique("alice");
		Set<UUID> ids = ConcurrentHashMap.newKeySet();

		Map<String, Integer> outcomes = race(50,
				i -> () -> ids.add(reservations.reserve(alice, show, List.of("A1"), "k1").reservationId()));

		assertThat(outcomes).containsExactlyEntriesOf(Map.of("ok", 50));
		assertThat(ids).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = ?", Integer.class, alice))
			.isEqualTo(1);
	}

	@Test
	void concurrentSameKeyDifferentBodiesProduceOneReservation() throws Exception { // case 7
		UUID show = createShow(4, "A1", "A2");
		String alice = unique("alice");

		Map<String, Integer> outcomes = race(50,
				i -> () -> reservations.reserve(alice, show, List.of(i % 2 == 0 ? "A1" : "A2"), "k1"));

		assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("ok", 25, "key_reused", 25));
		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 1));
	}

	@Test
	void declineIsNotStoredSoRetryIsReevaluated() { // cases 8 and 9
		UUID show = createShow(4, "A1", "A2");
		String alice = unique("alice");
		reservations.reserve(unique("bob"), show, List.of("A1"), "k");

		assertThatThrownBy(() -> reservations.reserve(alice, show, List.of("A1"), "k1"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.reason()).isEqualTo("seat_taken"));
		// The failed attempt left no key behind, so the key is still free for a fresh request.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = ?", Integer.class, alice))
			.isZero();
	}

	@Test
	void keysAreScopedPerUser() { // case 11
		UUID show = createShow(4, "A1", "A2");
		ReservationResponse a = reservations.reserve(unique("alice"), show, List.of("A1"), "shared");
		ReservationResponse b = reservations.reserve(unique("bob"), show, List.of("A2"), "shared");
		assertThat(a.reservationId()).isNotEqualTo(b.reservationId());
	}
}

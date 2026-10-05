package com.example.seats.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.seats.ApiTestSupport;
import com.example.seats.model.ShowResponse.Counts;

class ReservationConcurrencyTest extends ApiTestSupport {

	@Autowired
	ReservationService reservations;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void hotSeatHasExactlyOneWinner() throws Exception {
		UUID show = createShow(4, "A11", "A12", "A13");

		Map<String, Integer> outcomes = race(500,
				i -> () -> reservations.reserve(unique("user" + i), show, List.of("A12"), "k"));

		assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("ok", 1, "seat_taken", 499));
		assertThat(counts(show)).isEqualTo(new Counts(2, 0, 1));
	}

	@Test
	void mirroredMultiSeatRequestsNeverDeadlock() throws Exception {
		for (int round = 0; round < 50; round++) {
			UUID show = createShow(4, "A1", "A2");
			Map<String, Integer> outcomes = race(2, i -> () -> reservations.reserve(unique("user" + i), show,
					i == 0 ? List.of("A1", "A2") : List.of("A2", "A1"), "k"));

			assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("ok", 1, "seat_taken", 1));
		}
	}

	@Test
	void overlappingRequestsNeverShareASeat() throws Exception {
		String[] labels = { "B1", "B2", "B3", "B4", "B5", "B6" };
		UUID show = createShow(4, labels);

		// Each task asks for two distinct seats: a, and a + (1..5) wrapping around.
		Map<String, Integer> outcomes = race(300, i -> () -> reservations.reserve(unique("user" + i), show,
				List.of(labels[i % 6], labels[(i % 6 + 1 + (i / 6) % 5) % 6]), "k"));

		assertThat(outcomes.keySet()).containsOnly("ok", "seat_taken");
		int winners = outcomes.get("ok");
		Counts counts = counts(show);
		assertThat(counts.confirmed()).isEqualTo(2 * winners);
		assertThat(counts.available() + counts.held() + counts.confirmed()).isEqualTo(labels.length);

		// Each confirmed seat points at exactly one reservation, and that reservation lists the seat.
		Integer mismatched = jdbc.queryForObject("""
				SELECT count(*) FROM seats s
				LEFT JOIN reservations r ON r.id = s.reservation_id
				WHERE s.show_id = ? AND s.status = 'confirmed'
				  AND (r.id IS NULL OR NOT s.label = ANY(r.seats) OR r.user_id <> s.user_id)
				""", Integer.class, show);
		assertThat(mismatched).isZero();
	}
}

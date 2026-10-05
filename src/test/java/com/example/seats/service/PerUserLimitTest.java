package com.example.seats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.seats.ApiTestSupport;
import com.example.seats.exception.ApiException;
import com.example.seats.model.ShowResponse.Counts;

class PerUserLimitTest extends ApiTestSupport {

	private static final String[] TEN = { "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10" };

	@Autowired
	ReservationService reservations;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void limitCountsSeatsAcrossReservations() {
		UUID show = createShow(2, "A1", "A2", "A3");
		String alice = unique("alice");

		reservations.reserve(alice, show, List.of("A1"), "k1");
		assertDeclined(() -> reservations.reserve(alice, show, List.of("A2", "A3"), "k2"));
		reservations.reserve(alice, show, List.of("A2"), "k3");
		assertDeclined(() -> reservations.reserve(alice, show, List.of("A3"), "k4"));

		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 2));
	}

	@Test
	void singleRequestOverTheLimitIsDeclined() {
		UUID show = createShow(4, TEN);
		assertDeclined(() -> reservations.reserve(unique("alice"), show, List.of("A1", "A2", "A3", "A4", "A5"), "k"));
		assertThat(counts(show)).isEqualTo(new Counts(10, 0, 0));
	}

	@Test
	void limitIsPerShow() {
		UUID show1 = createShow(1, "A1");
		UUID show2 = createShow(1, "A1");
		String alice = unique("alice");
		reservations.reserve(alice, show1, List.of("A1"), "k1");
		reservations.reserve(alice, show2, List.of("A1"), "k2");
	}

	@Test
	void replayDoesNotConsumeTheLimit() {
		UUID show = createShow(1, "A1");
		String alice = unique("alice");
		var first = reservations.reserve(alice, show, List.of("A1"), "k1");
		assertThat(reservations.reserve(alice, show, List.of("A1"), "k1")).isEqualTo(first);
	}

	@Test
	void tenParallelReservesOnLimitFourYieldFour() throws Exception {
		UUID show = createShow(4, TEN);
		String alice = unique("alice");

		Map<String, Integer> outcomes = race(10,
				i -> () -> reservations.reserve(alice, show, List.of(TEN[i]), "k" + i));

		assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("ok", 4, "per_user_limit", 6));
		assertThat(counts(show)).isEqualTo(new Counts(6, 0, 4));
		assertThat(seatCount(alice, show)).isEqualTo(4);
	}

	@Test
	void parallelMultiSeatReservesStayWithinTheLimit() throws Exception {
		UUID show = createShow(4, TEN);
		String alice = unique("alice");

		Map<String, Integer> outcomes = race(5,
				i -> () -> reservations.reserve(alice, show, List.of(TEN[2 * i], TEN[2 * i + 1]), "k" + i));

		assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("ok", 2, "per_user_limit", 3));
		assertThat(counts(show).confirmed()).isEqualTo(4);
		assertThat(seatCount(alice, show)).isEqualTo(4);
	}

	@Test
	void seatTakenDoesNotConsumeTheLimit() {
		UUID show = createShow(1, "A1", "A2");
		String alice = unique("alice");
		reservations.reserve(unique("bob"), show, List.of("A1"), "k");

		assertThatThrownBy(() -> reservations.reserve(alice, show, List.of("A1"), "k1"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.reason()).isEqualTo("seat_taken"));
		reservations.reserve(alice, show, List.of("A2"), "k2");
	}

	private int seatCount(String user, UUID show) {
		return jdbc.queryForObject("SELECT seat_count FROM user_show_counts WHERE user_id = ? AND show_id = ?",
				Integer.class, user, show);
	}

	private static void assertDeclined(Runnable reserve) {
		assertThatThrownBy(reserve::run)
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.reason()).isEqualTo("per_user_limit"));
	}
}

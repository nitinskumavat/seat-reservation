package com.example.seats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.seats.ApiTestSupport;
import com.example.seats.exception.ApiException;
import com.example.seats.model.ReservationResponse;
import com.example.seats.model.ReservationStatus;
import com.example.seats.model.ShowResponse.Counts;

class CancelTest extends ApiTestSupport {

	@Autowired
	ReservationService reservations;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void ownerCancelsAndSeatBecomesRebookable() throws Exception {
		UUID show = createShow(4, "A1", "A2");
		String alice = unique("alice");
		ReservationResponse r = reservations.reserve(alice, show, List.of("A1", "A2"), "k1");

		mvc.perform(post("/reservations/" + r.reservationId() + "/cancel").header("Authorization", "Bearer " + token(alice)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("cancelled"))
			.andExpect(jsonPath("$.seats.length()").value(2));

		assertThat(counts(show)).isEqualTo(new Counts(2, 0, 0));
		reservations.reserve(unique("bob"), show, List.of("A1", "A2"), "k");
		assertThat(counts(show)).isEqualTo(new Counts(0, 0, 2));
	}

	@Test
	void onlyTheOwnerCanCancel() throws Exception {
		UUID show = createShow(4, "A1");
		ReservationResponse r = reservations.reserve(unique("alice"), show, List.of("A1"), "k1");
		String url = "/reservations/" + r.reservationId() + "/cancel";

		mvc.perform(post(url).header("Authorization", "Bearer " + token(unique("mallory"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.reason").value("not_found"));
		mvc.perform(post(url)).andExpect(status().isUnauthorized());
		mvc.perform(post("/reservations/" + UUID.randomUUID() + "/cancel")
				.header("Authorization", "Bearer " + token(unique("alice"))))
			.andExpect(status().isNotFound());

		assertThat(counts(show)).isEqualTo(new Counts(0, 0, 1));
	}

	@Test
	void cancelIsIdempotent() {
		UUID show = createShow(4, "A1");
		String alice = unique("alice");
		ReservationResponse r = reservations.reserve(alice, show, List.of("A1"), "k1");

		reservations.cancel(alice, r.reservationId());
		assertThat(reservations.cancel(alice, r.reservationId()).status()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(seatCount(alice, show)).isZero();
	}

	@Test
	void cancelFreesTheUsersLimit() {
		UUID show = createShow(1, "A1", "A2");
		String alice = unique("alice");
		ReservationResponse r = reservations.reserve(alice, show, List.of("A1"), "k1");
		reservations.cancel(alice, r.reservationId());
		reservations.reserve(alice, show, List.of("A2"), "k2");
	}

	@Test
	void staleCancelNeverFreesSomeoneElsesSeat() {
		UUID show = createShow(4, "A1");
		String alice = unique("alice");
		ReservationResponse r = reservations.reserve(alice, show, List.of("A1"), "k1");
		reservations.cancel(alice, r.reservationId());
		String bob = unique("bob");
		reservations.reserve(bob, show, List.of("A1"), "k");

		reservations.cancel(alice, r.reservationId());

		assertThat(counts(show)).isEqualTo(new Counts(0, 0, 1));
		assertThat(jdbc.queryForObject("SELECT user_id FROM seats WHERE show_id = ? AND label = 'A1'", String.class,
				show)).isEqualTo(bob);
	}

	@Test
	void replayAfterCancelReturnsCancelledAndDoesNotRebook() { // case 10
		UUID show = createShow(4, "A1");
		String alice = unique("alice");
		ReservationResponse r = reservations.reserve(alice, show, List.of("A1"), "k1");
		reservations.cancel(alice, r.reservationId());

		ReservationResponse replay = reservations.reserve(alice, show, List.of("A1"), "k1");
		assertThat(replay.reservationId()).isEqualTo(r.reservationId());
		assertThat(replay.status()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 0));
	}

	@Test
	void declinedKeySucceedsOnceTheSeatIsFreed() { // case 9
		UUID show = createShow(4, "A1");
		String bob = unique("bob");
		ReservationResponse held = reservations.reserve(bob, show, List.of("A1"), "k");
		String alice = unique("alice");

		assertThatThrownBy(() -> reservations.reserve(alice, show, List.of("A1"), "k1"))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.reason()).isEqualTo("seat_taken"));
		reservations.cancel(bob, held.reservationId());
		assertThat(reservations.reserve(alice, show, List.of("A1"), "k1").userId()).isEqualTo(alice);
	}

	@Test
	void parallelCancelsDecrementOnce() throws Exception {
		UUID show = createShow(4, "A1", "A2");
		String alice = unique("alice");
		ReservationResponse r = reservations.reserve(alice, show, List.of("A1", "A2"), "k1");

		Map<String, Integer> outcomes = race(20, i -> () -> reservations.cancel(alice, r.reservationId()));

		assertThat(outcomes).containsExactlyEntriesOf(Map.of("ok", 20));
		assertThat(seatCount(alice, show)).isZero();
		assertThat(counts(show)).isEqualTo(new Counts(2, 0, 0));
	}

	@Test
	void cancelRacingReservesNeverDeadlocksOrDoubleSells() throws Exception {
		for (int round = 0; round < 20; round++) {
			UUID show = createShow(4, "A1", "A2", "A3");
			String alice = unique("alice");
			ReservationResponse r = reservations.reserve(alice, show, List.of("A1", "A2", "A3"), "k1");

			Map<String, Integer> outcomes = race(30, i -> i == 0
					? () -> reservations.cancel(alice, r.reservationId())
					: () -> reservations.reserve(unique("user" + i), show,
							i % 2 == 0 ? List.of("A3", "A1") : List.of("A2", "A3"), "k"));

			assertThat(outcomes.keySet()).containsOnly("ok", "seat_taken");
			Counts counts = counts(show);
			assertThat(counts.available() + counts.held() + counts.confirmed()).isEqualTo(3);
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id
					WHERE s.show_id = ? AND s.status = 'confirmed'
					  AND (r.status <> 'confirmed' OR NOT s.label = ANY(r.seats) OR r.user_id <> s.user_id)
					""", Integer.class, show)).isZero();
		}
	}

	private int seatCount(String user, UUID show) {
		return jdbc.queryForObject("SELECT seat_count FROM user_show_counts WHERE user_id = ? AND show_id = ?",
				Integer.class, user, show);
	}
}

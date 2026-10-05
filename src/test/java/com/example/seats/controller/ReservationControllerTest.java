package com.example.seats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.seats.ApiTestSupport;
import com.example.seats.model.ShowResponse.Counts;

class ReservationControllerTest extends ApiTestSupport {

	@Test
	void reservesSeatsForTheTokenUser() throws Exception {
		UUID show = createShow(4, "A1", "A2", "A3");
		String alice = unique("alice");

		reserve(token(alice), show, "{\"seats\":[\"A2\",\"A1\"],\"idempotency_key\":\"k1\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reservation_id").isNotEmpty())
			.andExpect(jsonPath("$.show_id").value(show.toString()))
			.andExpect(jsonPath("$.user_id").value(alice))
			.andExpect(jsonPath("$.seats[0]").value("A1"))
			.andExpect(jsonPath("$.seats[1]").value("A2"))
			.andExpect(jsonPath("$.amount_paise").value(50000))
			.andExpect(jsonPath("$.status").value("confirmed"));

		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 2));
	}

	@Test
	void takenSeatIsACleanConflict() throws Exception {
		UUID show = createShow(4, "A1");
		reserve(token(unique("alice")), show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}")
			.andExpect(status().isCreated());

		reserve(token(unique("bob")), show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("seat_taken"));
	}

	@Test
	void multiSeatRequestIsAllOrNothing() throws Exception {
		UUID show = createShow(4, "A1", "A2");
		reserve(token(unique("alice")), show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}")
			.andExpect(status().isCreated());

		reserve(token(unique("bob")), show, "{\"seats\":[\"A1\",\"A2\"],\"idempotency_key\":\"k\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("seat_taken"));

		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 1));
	}

	@Test
	void unknownSeatOrShowIs404AndChangesNothing() throws Exception {
		UUID show = createShow(4, "A1");
		String t = token(unique("alice"));

		reserve(t, show, "{\"seats\":[\"A1\",\"Z9\"],\"idempotency_key\":\"k\"}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.reason").value("not_found"));
		reserve(t, UUID.randomUUID(), "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}")
			.andExpect(status().isNotFound());

		assertThat(counts(show)).isEqualTo(new Counts(1, 0, 0));
	}

	@Test
	void malformedRequestsAre400() throws Exception {
		UUID show = createShow(4, "A1", "A2");
		String t = token(unique("alice"));
		String[] invalid = {
				"{\"seats\":[\"A1\",\"A1\"],\"idempotency_key\":\"k\"}",
				"{\"seats\":[],\"idempotency_key\":\"k\"}",
				"{\"seats\":[\"A1\"]}",
				"{\"seats\":[\"A1\"],\"idempotency_key\":\"\"}" };
		for (String body : invalid) {
			reserve(t, show, body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("invalid_request"));
		}

		mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", "Bearer " + t)
				.header("Idempotency-Key", "header-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\":[\"A1\"],\"idempotency_key\":\"body-key\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void idempotencyKeyMayComeFromHeader() throws Exception {
		UUID show = createShow(4, "A1");
		mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", "Bearer " + token(unique("alice")))
				.header("Idempotency-Key", "header-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\":[\"A1\"]}"))
			.andExpect(status().isCreated());
	}

	@Test
	void spoofedUserIdInBodyIsIgnored() throws Exception {
		UUID show = createShow(4, "A1");
		String mallory = unique("mallory");

		reserve(token(mallory), show, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\",\"user_id\":\"victim\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user_id").value(mallory));
	}

	@Test
	void reserveRequiresToken() throws Exception {
		UUID show = createShow(4, "A1");
		mvc.perform(post("/shows/" + show + "/reserve").contentType(MediaType.APPLICATION_JSON)
				.content("{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}"))
			.andExpect(status().isUnauthorized());
	}
}

package com.example.seats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.example.seats.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.admin-token=test-admin")
@AutoConfigureMockMvc
class ShowControllerTest {

	private static final String SHOW = """
			{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}
			""";

	@Autowired
	MockMvc mvc;

	@Test
	void createRequiresAdminToken() throws Exception {
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON).content(SHOW))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/shows").header("X-Admin-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content(SHOW))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void createdShowHasEverySeatAvailableAndCountsReconcile() throws Exception {
		String body = mvc.perform(createShow(SHOW))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("friday-night"))
			.andExpect(jsonPath("$.price_paise").value(25000))
			.andExpect(jsonPath("$.per_user_limit").value(4))
			.andExpect(jsonPath("$.total_seats").value(3))
			.andExpect(jsonPath("$.counts.available").value(3))
			.andExpect(jsonPath("$.counts.held").value(0))
			.andExpect(jsonPath("$.counts.confirmed").value(0))
			.andExpect(jsonPath("$.seats[*].status").value(everyItem(is("available"))))
			.andReturn().getResponse().getContentAsString();

		String id = JsonPath.read(body, "$.id");
		String fetched = mvc.perform(get("/shows/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andReturn().getResponse().getContentAsString();

		int total = JsonPath.read(fetched, "$.total_seats");
		int available = JsonPath.read(fetched, "$.counts.available");
		int held = JsonPath.read(fetched, "$.counts.held");
		int confirmed = JsonPath.read(fetched, "$.counts.confirmed");
		assertThat(available + held + confirmed).isEqualTo(total);
	}

	@Test
	void customPerUserLimitIsStored() throws Exception {
		mvc.perform(createShow("""
				{"name":"s","seats":["A1"],"price_paise":100,"per_user_limit":2}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.per_user_limit").value(2));
	}

	@Test
	void invalidShowsAreRejected() throws Exception {
		String[] invalid = {
				"{\"name\":\"s\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":100}",
				"{\"name\":\"s\",\"seats\":[],\"price_paise\":100}",
				"{\"name\":\"s\",\"seats\":[\"A1\"],\"price_paise\":-1}",
				"{\"name\":\"s\",\"seats\":[\"A1\"]}",
				"{\"name\":\"\",\"seats\":[\"A1\"],\"price_paise\":100}",
				"{\"name\":\"s\",\"seats\":[\"A1\"],\"price_paise\":100,\"per_user_limit\":0}",
				"not json" };
		for (String body : invalid) {
			mvc.perform(createShow(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("invalid_request"));
		}
	}

	@Test
	void unknownShowIs404() throws Exception {
		mvc.perform(get("/shows/" + UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.reason").value("not_found"));
		mvc.perform(get("/shows/not-a-uuid")).andExpect(status().isBadRequest());
	}

	private static RequestBuilder createShow(String body) {
		return post("/shows").header("X-Admin-Token", "test-admin")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}
}

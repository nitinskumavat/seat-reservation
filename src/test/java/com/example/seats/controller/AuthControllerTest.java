package com.example.seats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.example.seats.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JwtDecoder decoder;

	@Autowired
	JwtEncoder encoder;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void issuesTokenWhoseSubjectIsTheUserAndUpsertsUser() throws Exception {
		String user = "alice-" + UUID.randomUUID();
		String token = mint(user);
		assertThat(decoder.decode(token).getSubject()).isEqualTo(user);

		mint(user);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, user)).isEqualTo(1);
	}

	@Test
	void invalidUserIdIsRejected() throws Exception {
		for (String body : new String[] { "{\"user_id\":\"\"}", "{}", "{\"user_id\":\"" + "x".repeat(65) + "\"}" }) {
			mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("invalid_request"));
		}
	}

	@Test
	void protectedRoutesRejectMissingOrBadTokens() throws Exception {
		String route = "/shows/" + UUID.randomUUID() + "/reserve";
		String otherSecret = sign(new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
				"some-other-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))),
				"mallory", Instant.now().plusSeconds(3600));
		String expired = sign(encoder, "bob", Instant.now().minusSeconds(3600));

		mvc.perform(post(route)).andExpect(status().isUnauthorized());
		for (String bad : new String[] { "garbage", otherSecret, expired }) {
			mvc.perform(post(route).header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void validTokenPassesSecurityOnUserRoutes() throws Exception {
		String token = mint("carol");
		mvc.perform(post("/shows/" + UUID.randomUUID() + "/reserve").header("Authorization", "Bearer " + token))
			.andExpect(r -> assertThat(r.getResponse().getStatus()).isNotIn(401, 403));
	}

	@Test
	void userTokenCannotCreateShows() throws Exception {
		mvc.perform(post("/shows").header("Authorization", "Bearer " + mint("dave"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"s\",\"seats\":[\"A1\"],\"price_paise\":100}"))
			.andExpect(status().isForbidden());
	}

	private String mint(String user) throws Exception {
		String body = mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"" + user + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user_id").value(user))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	private static String sign(JwtEncoder encoder, String subject, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(subject)
			.issuedAt(expiresAt.minusSeconds(7200))
			.expiresAt(expiresAt)
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}
}

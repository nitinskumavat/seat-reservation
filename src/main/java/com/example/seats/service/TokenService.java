package com.example.seats.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.seats.model.TokenResponse;
import com.example.seats.repository.UserRepository;

/**
 * Demo auth: issues a token for any user id. A real deployment would get tokens from an
 * identity provider; the rest of the service only reads the token's subject.
 */
@Service
public class TokenService {

	private static final Duration TTL = Duration.ofHours(24);

	private final UserRepository users;
	private final JwtEncoder encoder;

	public TokenService(UserRepository users, JwtEncoder encoder) {
		this.users = users;
		this.encoder = encoder;
	}

	public TokenResponse issue(String userId) {
		users.upsert(userId);
		Instant now = Instant.now();
		Instant expiresAt = now.plus(TTL);
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(userId).issuedAt(now).expiresAt(expiresAt).build();
		String token = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new TokenResponse(token, userId, expiresAt);
	}
}

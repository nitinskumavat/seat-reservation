package com.example.seats.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

	private final JdbcTemplate jdbc;

	public UserRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Idempotent: minting a second token for the same user is a no-op here. */
	public void upsert(String id) {
		jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT (id) DO NOTHING", id);
	}
}

package com.example.seats.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserShowCountRepository {

	private final JdbcTemplate jdbc;

	public UserShowCountRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Adds n seats to the user's count for the show if that stays within the limit. The UPDATE
	 * row-locks the counter, so a user's parallel requests queue here and each sees the count
	 * left by the one before it. Returns false when the limit would be exceeded.
	 */
	public boolean tryAdd(String userId, UUID showId, int n, int limit) {
		jdbc.update("""
				INSERT INTO user_show_counts (user_id, show_id, seat_count) VALUES (?, ?, 0)
				ON CONFLICT (user_id, show_id) DO NOTHING
				""", userId, showId);
		return jdbc.update("""
				UPDATE user_show_counts SET seat_count = seat_count + ?
				WHERE user_id = ? AND show_id = ? AND seat_count + ? <= ?
				""", n, userId, showId, n, limit) == 1;
	}
}

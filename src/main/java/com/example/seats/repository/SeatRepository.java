package com.example.seats.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.seats.model.Seat;
import com.example.seats.model.SeatStatus;

@Repository
public class SeatRepository {

	private final JdbcTemplate jdbc;

	public SeatRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One statement for all seats, so large halls don't cost one round trip per seat. */
	public void insertAll(UUID showId, List<String> labels) {
		jdbc.update("INSERT INTO seats (show_id, label) SELECT ?, unnest(?::text[])", ps -> {
			ps.setObject(1, showId);
			ps.setArray(2, ps.getConnection().createArrayOf("text", labels.toArray()));
		});
	}

	/**
	 * Row-locks the requested seats in label order. Every transaction locks in the same order,
	 * so two multi-seat requests can never each hold a seat the other is waiting for. A waiter
	 * re-reads the row once the lock is released, so it sees the winner's committed status.
	 */
	public List<Seat> lockForUpdate(UUID showId, List<String> labels) {
		return jdbc.query("""
				SELECT label, status FROM seats
				WHERE show_id = ? AND label = ANY(?)
				ORDER BY label
				FOR UPDATE
				""", (rs, i) -> new Seat(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))),
				showId, labels.toArray(String[]::new));
	}

	/** Only called while holding the row locks taken by {@link #lockForUpdate}. */
	public void confirm(UUID showId, List<String> labels, UUID reservationId, String userId) {
		jdbc.update("""
				UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?
				WHERE show_id = ? AND label = ANY(?)
				""", reservationId, userId, showId, labels.toArray(String[]::new));
	}

	/** A single SELECT is one snapshot, so the statuses it returns are mutually consistent. */
	public List<Seat> findByShow(UUID showId) {
		return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY label",
				(rs, i) -> new Seat(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))), showId);
	}
}

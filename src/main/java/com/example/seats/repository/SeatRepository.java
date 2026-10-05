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

	/** A single SELECT is one snapshot, so the statuses it returns are mutually consistent. */
	public List<Seat> findByShow(UUID showId) {
		return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY label",
				(rs, i) -> new Seat(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))), showId);
	}
}

package com.example.seats.model;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
		UUID id,
		String name,
		long pricePaise,
		int perUserLimit,
		int totalSeats,
		Counts counts,
		List<Seat> seats) {

	public record Counts(int available, int held, int confirmed) {
	}

	/** Counts are derived from the same seat list, so they always add up to that list. */
	public static ShowResponse of(Show show, List<Seat> seats) {
		int available = 0, held = 0, confirmed = 0;
		for (Seat seat : seats) {
			switch (seat.status()) {
				case AVAILABLE -> available++;
				case HELD -> held++;
				case CONFIRMED -> confirmed++;
			}
		}
		return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
				show.totalSeats(), new Counts(available, held, confirmed), seats);
	}
}

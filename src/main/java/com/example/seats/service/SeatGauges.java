package com.example.seats.service;

import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.seats.model.SeatStatus;
import com.example.seats.repository.SeatRepository;
import com.example.seats.repository.ShowRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * seats{show, status} gauges. Each value is read from the database when metrics are scraped,
 * so it always matches what GET /shows/{id} reports.
 */
@Component
public class SeatGauges {

	private final MeterRegistry registry;
	private final SeatRepository seats;
	private final ShowRepository shows;

	public SeatGauges(MeterRegistry registry, SeatRepository seats, ShowRepository shows) {
		this.registry = registry;
		this.seats = seats;
		this.shows = shows;
	}

	@EventListener(ApplicationReadyEvent.class)
	void registerExistingShows() {
		shows.findAllIds().forEach(this::register);
	}

	void register(UUID showId) {
		for (SeatStatus status : SeatStatus.values()) {
			Gauge.builder("seats", () -> seats.countByStatus(showId, status))
				.tag("show", showId.toString())
				.tag("status", status.json())
				.register(registry);
		}
	}
}

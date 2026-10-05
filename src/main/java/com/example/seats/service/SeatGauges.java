package com.example.seats.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.seats.model.SeatStatus;
import com.example.seats.repository.SeatRepository;
import com.example.seats.repository.ShowRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * seats{show, status} gauges, read from the database when metrics are scraped so they match
 * GET /shows/{id}. One grouped query serves every gauge in a scrape (cached for a second), so a
 * scrape costs one pool connection no matter how many shows exist.
 */
@Component
public class SeatGauges {

	private static final long MAX_AGE_NANOS = 1_000_000_000L;

	private final MeterRegistry registry;
	private final SeatRepository seats;
	private final ShowRepository shows;

	// A lock rather than synchronized: on Java 21 a virtual thread blocking on JDBC inside
	// synchronized would pin its carrier thread.
	private final ReentrantLock lock = new ReentrantLock();
	private Map<String, Integer> counts = Map.of();
	private long countedAt;

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
			Gauge.builder("seats", () -> counts().getOrDefault(SeatRepository.countKey(showId, status), 0))
				.tag("show", showId.toString())
				.tag("status", status.json())
				.register(registry);
		}
	}

	private Map<String, Integer> counts() {
		lock.lock();
		try {
			if (System.nanoTime() - countedAt > MAX_AGE_NANOS || counts.isEmpty()) {
				counts = seats.countAllByShowAndStatus();
				countedAt = System.nanoTime();
			}
			return counts;
		}
		finally {
			lock.unlock();
		}
	}
}

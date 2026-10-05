package com.example.seats.service;

import java.util.HashSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seats.exception.BadRequestException;
import com.example.seats.exception.NotFoundException;
import com.example.seats.model.CreateShowRequest;
import com.example.seats.model.Show;
import com.example.seats.model.ShowResponse;
import com.example.seats.repository.SeatRepository;
import com.example.seats.repository.ShowRepository;

@Service
public class ShowService {

	private static final int DEFAULT_PER_USER_LIMIT = 4;

	private final ShowRepository shows;
	private final SeatRepository seats;
	private final SeatGauges seatGauges;

	public ShowService(ShowRepository shows, SeatRepository seats, SeatGauges seatGauges) {
		this.shows = shows;
		this.seats = seats;
		this.seatGauges = seatGauges;
	}

	@Transactional
	public ShowResponse create(CreateShowRequest req) {
		if (new HashSet<>(req.seats()).size() != req.seats().size()) {
			throw new BadRequestException("duplicate seat labels");
		}
		int limit = req.perUserLimit() != null ? req.perUserLimit() : DEFAULT_PER_USER_LIMIT;
		Show show = new Show(UUID.randomUUID(), req.name(), req.pricePaise(), limit, req.seats().size());
		shows.insert(show);
		seats.insertAll(show.id(), req.seats());
		seatGauges.register(show.id());
		return ShowResponse.of(show, seats.findByShow(show.id()));
	}

	public ShowResponse get(UUID id) {
		Show show = shows.findById(id).orElseThrow(() -> new NotFoundException("show not found"));
		return ShowResponse.of(show, seats.findByShow(id));
	}
}

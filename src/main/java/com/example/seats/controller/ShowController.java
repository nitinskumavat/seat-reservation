package com.example.seats.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats.model.CreateShowRequest;
import com.example.seats.model.ShowResponse;
import com.example.seats.service.ShowService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/shows")
public class ShowController {

	private final ShowService showService;

	public ShowController(ShowService showService) {
		this.showService = showService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ShowResponse create(@Valid @RequestBody CreateShowRequest req) {
		return showService.create(req);
	}

	@GetMapping("/{id}")
	public ShowResponse get(@PathVariable UUID id) {
		return showService.get(id);
	}
}

package com.example.seats.model;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateShowRequest(
		@NotBlank String name,
		@NotEmpty List<@NotBlank String> seats,
		@NotNull @PositiveOrZero Long pricePaise,
		@Positive Integer perUserLimit) {
}

package com.example.seats.model;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Any user_id field a client sends is not part of this record, so it is ignored. */
public record ReserveRequest(
		@NotEmpty List<@NotBlank String> seats,
		@Size(max = 128) String idempotencyKey) {
}

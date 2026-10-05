package com.example.seats;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.seats.exception.ApiException;
import com.example.seats.model.CreateShowRequest;
import com.example.seats.model.ShowResponse;
import com.example.seats.service.ShowService;
import com.example.seats.service.TokenService;

/** Shared setup for tests that create shows, mint tokens and reserve. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.admin-token=test-admin")
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected ShowService showService;

	@Autowired
	protected TokenService tokenService;

	protected UUID createShow(int perUserLimit, String... seats) {
		return showService.create(new CreateShowRequest("test-show", List.of(seats), 25000L, perUserLimit)).id();
	}

	protected String token(String user) {
		return tokenService.issue(user).token();
	}

	protected ShowResponse.Counts counts(UUID showId) {
		return showService.get(showId).counts();
	}

	protected ResultActions reserve(String token, UUID showId, String json) throws Exception {
		return mvc.perform(post("/shows/" + showId + "/reserve").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	protected static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	/**
	 * Runs n tasks released at the same instant on virtual threads and tallies outcomes:
	 * "ok" for success, the ApiException reason for a domain decline, or the exception class
	 * name for anything unexpected.
	 */
	protected static Map<String, Integer> race(int n, IntFunction<Runnable> task) throws Exception {
		Map<String, Integer> outcomes = new ConcurrentHashMap<>();
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				Runnable r = task.apply(i);
				futures.add(pool.submit(() -> {
					start.await();
					String outcome;
					try {
						r.run();
						outcome = "ok";
					}
					catch (ApiException e) {
						outcome = e.reason();
					}
					catch (RuntimeException e) {
						outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
					}
					outcomes.merge(outcome, 1, Integer::sum);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get();
			}
		}
		return outcomes;
	}
}

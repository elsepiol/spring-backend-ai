import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A tiny load generator, so Level 4 can be measured with nothing but a JDK (no install, works on
 * Windows too). N workers each send one request, wait for the answer, and send the next, for a
 * fixed time. It reports requests per second, latency percentiles and status codes.
 *
 *   java scripts/LoadTest.java URL [workers=50] [seconds=15] [Header:value ...]
 *   java scripts/LoadTest.java http://localhost:8080/api/v1/products/1 50 15
 *   java scripts/LoadTest.java http://localhost:8080/api/v1/products/1 50 15 'If-None-Match:"0"'
 *
 * The first 3 seconds are a warm-up (JIT compilation, filling caches and pools) and not counted.
 * Run it on the same machine as the app and the numbers include both, so they are a comparison
 * between endpoints, not a benchmark of your server.
 */
public class LoadTest {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: java scripts/LoadTest.java URL [workers] [seconds] [Header:value ...]");
			System.exit(2);
		}
		URI uri = URI.create(args[0]);
		int workers = args.length > 1 ? Integer.parseInt(args[1]) : 50;
		int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 15;
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
		for (int i = 3; i < args.length; i++) {
			int colon = args[i].indexOf(':');
			builder.header(args[i].substring(0, colon).trim(), args[i].substring(colon + 1).trim());
		}
		HttpRequest request = builder.build();
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

		long warmupEnds = System.nanoTime() + 3_000_000_000L;
		long testEnds = warmupEnds + seconds * 1_000_000_000L;
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		List<Future<Result>> futures = new ArrayList<>();
		for (int w = 0; w < workers; w++) {
			futures.add(pool.submit(() -> {
				Result result = new Result();
				while (true) {
					long start = System.nanoTime();
					if (start >= testEnds) {
						return result;
					}
					String outcome;
					try {
						outcome = String.valueOf(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
					}
					catch (Exception ex) {
						outcome = ex.getClass().getSimpleName();
					}
					if (start >= warmupEnds) {
						result.latenciesMicros.add((System.nanoTime() - start) / 1_000);
						result.outcomes.merge(outcome, 1, Integer::sum);
					}
				}
			}));
		}
		List<Long> latencies = new ArrayList<>();
		Map<String, Integer> outcomes = new TreeMap<>();
		for (Future<Result> future : futures) {
			Result result = future.get();
			latencies.addAll(result.latenciesMicros);
			result.outcomes.forEach((k, v) -> outcomes.merge(k, v, Integer::sum));
		}
		pool.shutdown();

		Collections.sort(latencies);
		System.out.printf("%s  workers=%d  seconds=%d%n", uri, workers, seconds);
		System.out.printf("requests/s: %.0f%n", latencies.size() / (double) seconds);
		System.out.printf("latency ms: p50=%.2f p95=%.2f p99=%.2f max=%.2f%n", pct(latencies, 50), pct(latencies, 95),
				pct(latencies, 99), latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1) / 1000.0);
		System.out.println("outcomes:   " + outcomes);
	}

	private static double pct(List<Long> sorted, int p) {
		if (sorted.isEmpty()) {
			return 0;
		}
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1)) / 1000.0;
	}

	private static final class Result {

		final List<Long> latenciesMicros = new ArrayList<>();

		final Map<String, Integer> outcomes = new TreeMap<>();

	}

}

package dev.elsepiol.shop.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Runs many tasks at (nearly) the same instant so that race conditions actually happen.
 * Every thread waits at a starting gate; the gate opens once all are ready.
 */
public final class Concurrently {

	private Concurrently() {
	}

	/**
	 * Returns one entry per task: its result, or the exception it threw (as a Throwable
	 * object, so a test can count successes and each kind of failure).
	 */
	public static List<Object> runAtOnce(int count, IntFunction<Callable<Object>> taskFor) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch ready = new CountDownLatch(count);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				Callable<Object> work = taskFor.apply(i);
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					try {
						return work.call();
					}
					catch (Throwable t) {
						return t;
					}
				}));
			}
			if (!ready.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("threads did not start");
			}
			go.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> future : futures) {
				results.add(future.get(120, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	public static long countOf(List<Object> results, Class<?> type) {
		return results.stream().filter(type::isInstance).count();
	}

}

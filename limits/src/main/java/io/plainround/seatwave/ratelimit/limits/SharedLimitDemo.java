package io.plainround.seatwave.ratelimit.limits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

/**
 * One fan's holds, sent at the same moment through three gateway instances, against three ways of
 * keeping the fan's bucket (5 tokens; time is frozen, so nothing refills and the bound is exactly
 * 5):
 *
 * <ol>
 *   <li>a bucket in each instance's own memory;
 *   <li>one shared bucket that each instance reads, decides on, and writes back in two steps, with
 *       a store round trip between them;
 *   <li>one shared bucket changed in one atomic step, the way a Redis script changes it.
 * </ol>
 *
 * <p>The shared store is a {@link ConcurrentHashMap} standing in for Redis: {@code get} and {@code
 * put} are two separate commands, {@code compute} runs the whole check-and-take for one key while
 * no other update of that key can run, which is what Redis guarantees for one script.
 */
public final class SharedLimitDemo {

  private static final int INSTANCES = 3;
  private static final int REQUESTS_PER_INSTANCE = 100;
  private static final long CAPACITY = 5;
  private static final Duration REFILL_PERIOD = Duration.ofSeconds(10);
  private static final long FROZEN_NOW_MILLIS = 0;
  // An assumed 0.5 ms between reading the bucket and writing it back: one store round trip.
  private static final long STORE_ROUND_TRIP_NANOS = 500_000;
  private static final int RUNS = 5;
  private static final String FAN_KEY = "rl:holds:fan-42";

  private SharedLimitDemo() {}

  /**
   * Runs each way five times and prints how many of the 300 holds were allowed.
   *
   * @param arguments none are read
   * @throws InterruptedException if the run is interrupted while waiting for its requests
   */
  public static void main(String[] arguments) throws InterruptedException {
    System.out.println(
        "one fan, 300 holds at once, 100 through each of 3 gateway instances; bucket of 5,"
            + " time frozen, so at most 5 may pass");
    System.out.println(
        "way the bucket is kept                         allowed in 5 runs   bound 5 kept");
    List<Long> local = runs(SharedLimitDemo::localBuckets);
    print("1 one bucket per instance, in its memory", local);
    List<Long> readThenWrite = runs(SharedLimitDemo::sharedReadThenWrite);
    print("2 shared, read then write (two commands)", readThenWrite);
    List<Long> atomic = runs(SharedLimitDemo::sharedAtomic);
    print("3 shared, one atomic check-and-take", atomic);
  }

  private static void print(String way, List<Long> allowedPerRun) {
    boolean isBoundKept = allowedPerRun.stream().allMatch(allowed -> allowed <= CAPACITY);
    System.out.println(
        String.format(Locale.ROOT, "%-46s %-19s %s", way, allowedPerRun, isBoundKept));
  }

  private static List<Long> runs(Way way) throws InterruptedException {
    List<Long> allowedPerRun = new ArrayList<>();
    for (int run = 0; run < RUNS; run++) {
      allowedPerRun.add(way.run());
    }
    return List.copyOf(allowedPerRun);
  }

  private static long localBuckets() throws InterruptedException {
    List<TokenBucket> perInstance =
        IntStream.range(0, INSTANCES)
            .mapToObj(instance -> new TokenBucket(CAPACITY, 1, REFILL_PERIOD, FROZEN_NOW_MILLIS))
            .toList();
    return sendAll(instance -> perInstance.get(instance).tryAcquire(FROZEN_NOW_MILLIS).isAllowed());
  }

  private static long sharedReadThenWrite() throws InterruptedException {
    ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();
    return sendAll(instance -> {
      long tokens = store.getOrDefault(FAN_KEY, CAPACITY);
      // Every other instance may read the same value while this one is on its way back.
      LockSupport.parkNanos(STORE_ROUND_TRIP_NANOS);
      if (tokens < 1) {
        return false;
      }
      store.put(FAN_KEY, tokens - 1);
      return true;
    });
  }

  private static long sharedAtomic() throws InterruptedException {
    ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();
    return sendAll(instance -> {
      boolean[] isAllowed = {false};
      // The read, the decision and the write happen as one step for this key, like one script.
      store.compute(
          FAN_KEY,
          (key, tokens) -> {
            long current = tokens == null ? CAPACITY : tokens;
            isAllowed[0] = current >= 1;
            return isAllowed[0] ? current - 1 : current;
          });
      LockSupport.parkNanos(STORE_ROUND_TRIP_NANOS);
      return isAllowed[0];
    });
  }

  // Starts every request on its own virtual thread, releases them all together, and counts the
  // allowed ones.
  private static long sendAll(Instance gateway) throws InterruptedException {
    AtomicLong allowed = new AtomicLong();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int instance = 0; instance < INSTANCES; instance++) {
        int instanceIndex = instance;
        for (int request = 0; request < REQUESTS_PER_INSTANCE; request++) {
          requests.submit(() -> {
            start.await();
            if (gateway.handle(instanceIndex)) {
              allowed.incrementAndGet();
            }
            return null;
          });
        }
      }
      start.countDown();
    }
    return allowed.get();
  }

  @FunctionalInterface
  private interface Instance {
    boolean handle(int instanceIndex);
  }

  @FunctionalInterface
  private interface Way {
    long run() throws InterruptedException;
  }
}

package io.plainround.seatwave.ratelimit.limits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * A stand-in booking service near its knee: 64 workers, each hold taking 40 ms, so it can finish at
 * most 64 / 0.040 s = 1,600 holds per second. For one second, holds arrive on a fixed schedule at
 * 3,200 per second, twice that, as an open-loop crowd does (Module 1: the crowd does not wait for
 * answers). It runs twice: once queueing every hold, once shedding holds beyond 64 in flight with
 * an immediate refusal, and prints what the admitted holds waited.
 *
 * <p>The timings are real and vary a little from run to run; the shape does not.
 */
public final class ShedDemo {

  private static final int WORKERS = 64;
  private static final long WORK_MILLIS = 40;
  private static final int ARRIVALS_PER_SECOND = 3_200;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double P50 = 0.50;
  private static final double P99 = 0.99;

  private ShedDemo() {}

  /**
   * Runs both ways and prints one line each.
   *
   * @param arguments none are read
   */
  public static void main(String[] arguments) {
    System.out.println(
        "3,200 holds per second for 1 s into 64 workers of 40 ms (capacity about 1,600 per"
            + " second)");
    System.out.println(
        "way                          arrived  served  shed  shed %  served p50 ms  p99 ms  max"
            + " ms");
    run("queue every hold", false);
    run("shed beyond 64 in flight", true);
  }

  private static void run(String way, boolean isShedding) {
    InFlightLimit limit = new InFlightLimit(WORKERS);
    List<Long> servedNanos = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger shed = new AtomicInteger();
    long intervalNanos = NANOS_PER_SECOND / ARRIVALS_PER_SECOND;
    try (ExecutorService workers = Executors.newFixedThreadPool(WORKERS)) {
      long startNanos = System.nanoTime();
      for (int arrival = 0; arrival < ARRIVALS_PER_SECOND; arrival++) {
        // Open loop: each hold arrives at its planned time, whatever happened to the ones before.
        long plannedNanos = startNanos + arrival * intervalNanos;
        LockSupport.parkNanos(plannedNanos - System.nanoTime());
        if (isShedding && !limit.tryEnter()) {
          shed.incrementAndGet();
          continue;
        }
        workers.submit(() -> {
          try {
            Thread.sleep(WORK_MILLIS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          } finally {
            if (isShedding) {
              limit.leave();
            }
          }
          // The fan's wait counts from the planned arrival, so time spent queued is included.
          servedNanos.add(System.nanoTime() - plannedNanos);
        });
      }
    }
    List<Long> sorted = servedNanos.stream().sorted().toList();
    System.out.println(
        String.format(
            Locale.ROOT,
            "%-28s %7d  %6d  %4d  %6.1f  %13.1f  %6.1f  %6.1f",
            way,
            ARRIVALS_PER_SECOND,
            sorted.size(),
            shed.get(),
            100.0 * shed.get() / ARRIVALS_PER_SECOND,
            percentileMillis(sorted, P50),
            percentileMillis(sorted, P99),
            sorted.getLast() / NANOS_PER_MILLI));
  }

  // Nearest rank: the smallest value with at least the given share of values at or below it.
  private static double percentileMillis(List<Long> sorted, double share) {
    int rank = (int) Math.ceil(share * sorted.size());
    return sorted.get(Math.max(0, rank - 1)) / NANOS_PER_MILLI;
  }
}

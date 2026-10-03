package dev.jobs.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long to wait before retrying: the base delay doubled for each attempt already
 * made, capped, then scaled by a random factor between a half and one.
 *
 * <p>The randomness matters when one outage fails many jobs at the same moment. Without
 * it they would all come back at the same moment too, and again, each wave hitting
 * whatever was struggling.
 */
public record Backoff(Duration base, Duration max) {

    public Duration after(int attemptsMade) {
        return after(attemptsMade, ThreadLocalRandom.current().nextDouble());
    }

    /** @param random in [0, 1); exposed so the arithmetic can be tested exactly */
    Duration after(int attemptsMade, double random) {
        long ceiling = ceilingMillis(attemptsMade);
        return Duration.ofMillis((long) (ceiling * (0.5 + random / 2)));
    }

    long ceilingMillis(int attemptsMade) {
        int doublings = Math.min(Math.max(attemptsMade - 1, 0), 30);
        long uncapped = base.toMillis() * (1L << doublings);
        return Math.min(uncapped, max.toMillis());
    }
}

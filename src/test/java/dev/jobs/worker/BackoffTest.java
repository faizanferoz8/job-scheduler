package dev.jobs.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffTest {

    private final Backoff backoff = new Backoff(Duration.ofSeconds(2), Duration.ofMinutes(5));

    @ParameterizedTest
    @CsvSource({"1, 2000", "2, 4000", "3, 8000", "4, 16000", "8, 256000", "9, 300000", "20, 300000"})
    void theCeilingDoublesWithEachAttemptUpToTheCap(int attemptsMade, long expectedMillis) {
        assertThat(backoff.ceilingMillis(attemptsMade)).isEqualTo(expectedMillis);
    }

    @Test
    void theDelayIsBetweenHalfTheCeilingAndTheCeiling() {
        assertThat(backoff.after(3, 0.0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.after(3, 0.5)).isEqualTo(Duration.ofSeconds(6));
        assertThat(backoff.after(3, 0.999999)).isBetween(Duration.ofMillis(7_990), Duration.ofSeconds(8));
    }

    @Test
    void jitterSpreadsRetriesThatWouldOtherwiseCoincide() {
        long distinct = java.util.stream.IntStream.range(0, 200).mapToObj(i -> backoff.after(4)).distinct().count();

        assertThat(distinct).isGreaterThan(150);
    }

    @Test
    void absurdAttemptCountsDoNotOverflow() {
        assertThat(backoff.ceilingMillis(1_000)).isEqualTo(300_000);
        assertThat(backoff.ceilingMillis(0)).isEqualTo(2_000);
    }

    @Test
    void aLeaseShorterThanTwoHeartbeatsIsRefused() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new WorkerSettings(
                        "w", "q", 1, Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ZERO, backoff));
    }
}

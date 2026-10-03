package dev.jobs.demo;

import dev.jobs.worker.JobContext;
import dev.jobs.worker.JobHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stand-ins for real work, each showing one behaviour of the scheduler. A real
 * deployment replaces these with its own {@link JobHandler} beans.
 */
@Configuration
class DemoHandlers {

    private static JobHandler handler(String type, Work work) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(JobContext context) throws Exception {
                work.run(context);
            }
        };
    }

    private interface Work {
        void run(JobContext context) throws Exception;
    }

    /** Takes {@code millis} to finish (default 2s). Stands in for work that takes a while. */
    @Bean
    JobHandler sleep() {
        return handler("sleep", context -> Thread.sleep(context.payload().path("millis").asLong(2_000)));
    }

    /** Fails its first {@code failures} attempts (default 2), then succeeds: a dependency that recovers. */
    @Bean
    JobHandler flaky() {
        return handler("flaky", context -> {
            Thread.sleep(context.payload().path("millis").asLong(500));
            int failures = context.payload().path("failures").asInt(2);
            if (context.attempt() <= failures) {
                throw new IllegalStateException("Upstream returned 503 (attempt " + context.attempt() + ")");
            }
        });
    }

    /** Never succeeds: a job with a bug in it, which should end up dead rather than retry forever. */
    @Bean
    JobHandler alwaysFails() {
        return handler("always-fails", context -> {
            Thread.sleep(300);
            throw new IllegalArgumentException("Customer 4411 has no billing address");
        });
    }
}

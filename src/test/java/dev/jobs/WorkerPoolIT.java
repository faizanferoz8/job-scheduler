package dev.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.jobs.job.Job;
import dev.jobs.job.JobAttempt;
import dev.jobs.job.JobStatus;
import dev.jobs.job.NewJob;
import dev.jobs.support.IntegrationTest;
import dev.jobs.worker.JobHandler;
import dev.jobs.worker.WorkerPool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerPoolIT extends IntegrationTest {

    private static final Duration SOON = Duration.ofSeconds(10);

    private Job enqueue(String type) {
        return store.enqueue(NewJob.of(queue, type, "{}")).job();
    }

    @Test
    void competingWorkersRunEveryJobExactlyOnce() {
        Map<UUID, AtomicInteger> runs = new ConcurrentHashMap<>();
        Set<String> threadsUsed = ConcurrentHashMap.newKeySet();
        JobHandler count = handler("count", context -> {
            runs.computeIfAbsent(context.job().id(), id -> new AtomicInteger()).incrementAndGet();
            threadsUsed.add(context.job().lockedBy());
            Thread.sleep(2);
        });
        int jobs = 400;
        for (int i = 0; i < jobs; i++) {
            enqueue("count");
        }

        for (int i = 0; i < 4; i++) {
            startWorker("w" + i, 5, count);
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> count("SUCCEEDED") == jobs);
        assertThat(runs).hasSize(jobs);
        assertThat(runs.values()).allSatisfy(n -> assertThat(n).hasValue(1));
        // The work really was shared; one worker did not just take everything.
        assertThat(threadsUsed).hasSize(4);
    }

    @Test
    void simultaneousClaimsNeverReturnTheSameJob() throws Exception {
        int jobs = 300;
        for (int i = 0; i < jobs; i++) {
            enqueue("anything");
        }
        int claimers = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Job>>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(claimers)) {
            for (int i = 0; i < claimers; i++) {
                String worker = "claimer-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    List<Job> mine = new ArrayList<>();
                    List<Job> batch;
                    while (!(batch = store.claim(queue, worker, 7, Duration.ofMinutes(1))).isEmpty()) {
                        mine.addAll(batch);
                    }
                    return mine;
                }));
            }
            start.countDown();
        }

        List<UUID> claimed = new ArrayList<>();
        for (Future<List<Job>> result : results) {
            result.get().forEach(job -> claimed.add(job.id()));
        }
        assertThat(claimed).hasSize(jobs).doesNotHaveDuplicates();
    }

    @Test
    void jobsRunInPriorityOrderThenOldestFirst() {
        List<String> order = new CopyOnWriteArrayList<>();
        JobHandler record = handler("record", context -> order.add(context.payload().get("name").asString()));
        store.enqueue(NewJob.of(queue, "record", "{\"name\":\"normal-1\"}"));
        store.enqueue(NewJob.of(queue, "record", "{\"name\":\"low\"}").withPriority(-5));
        store.enqueue(NewJob.of(queue, "record", "{\"name\":\"normal-2\"}"));
        store.enqueue(NewJob.of(queue, "record", "{\"name\":\"urgent\"}").withPriority(10));

        // One slot, so the order of execution is the order of claiming.
        startWorker("solo", 1, record);

        await().atMost(SOON).until(() -> order.size() == 4);
        assertThat(order).containsExactly("urgent", "normal-1", "normal-2", "low");
    }

    @Test
    void aDelayedJobDoesNotRunEarly() {
        AtomicBoolean ran = new AtomicBoolean();
        JobHandler mark = handler("mark", context -> ran.set(true));
        Job job = store.enqueue(NewJob.of(queue, "mark", "{}").withDelay(Duration.ofMillis(1_500))).job();
        startWorker("solo", 1, mark);

        await().during(Duration.ofMillis(1_000)).atMost(Duration.ofMillis(1_400)).until(() -> !ran.get());
        await().atMost(SOON).untilTrue(ran);
        assertThat(reload(job).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void aFailedJobIsRetriedAfterAGrowingDelayUntilItSucceeds() {
        JobHandler flaky = handler("flaky", context -> {
            if (context.attempt() < 3) {
                throw new IllegalStateException("not yet (attempt " + context.attempt() + ")");
            }
        });
        Job job = enqueue("flaky");
        startWorker("solo", 1, flaky);

        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.SUCCEEDED);

        List<JobAttempt> attempts = store.attempts(job.id());
        assertThat(attempts).extracting(JobAttempt::outcome).containsExactly("FAILED", "FAILED", "SUCCEEDED");
        assertThat(attempts.get(0).error()).isEqualTo("IllegalStateException: not yet (attempt 1)");
        // Base delay 200ms with jitter down to half: at least 100ms, then at least 200ms.
        Duration firstGap = Duration.between(attempts.get(0).finishedAt(), attempts.get(1).startedAt());
        Duration secondGap = Duration.between(attempts.get(1).finishedAt(), attempts.get(2).startedAt());
        assertThat(firstGap).isGreaterThanOrEqualTo(Duration.ofMillis(100));
        assertThat(secondGap).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        assertThat(reload(job).lastError()).isNull();
    }

    @Test
    void aJobThatNeverSucceedsEndsUpDeadAndCanBeRetriedByHand() {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean fixed = new AtomicBoolean();
        JobHandler broken = handler("broken", context -> {
            runs.incrementAndGet();
            if (!fixed.get()) {
                throw new IllegalArgumentException("bad input");
            }
        });
        Job job = store.enqueue(NewJob.of(queue, "broken", "{}").withMaxAttempts(3)).job();
        startWorker("solo", 1, broken);

        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.DEAD);
        assertThat(runs).hasValue(3);
        assertThat(reload(job).lastError()).isEqualTo("IllegalArgumentException: bad input");
        assertThat(reload(job).finishedAt()).isNotNull();
        // It stays dead: nothing picks it up again on its own.
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(1)).until(() -> runs.get() == 3);

        fixed.set(true);
        assertThat(store.retry(job.id())).isTrue();

        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.SUCCEEDED);
        assertThat(reload(job).attempts()).isEqualTo(1);
    }

    @Test
    void aJobWithNoHandlerFailsWithAnExplanationInsteadOfVanishing() {
        Job job = store.enqueue(NewJob.of(queue, "unheard-of", "{}").withMaxAttempts(1)).job();
        startWorker("solo", 1);

        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.DEAD);
        assertThat(reload(job).lastError()).contains("No handler is registered for job type 'unheard-of'");
    }

    @Test
    void aCrashedWorkersJobIsTakenOverAndItsLateResultIsRefused() throws Exception {
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch letTheZombieFinish = new CountDownLatch(1);
        CountDownLatch zombieFinished = new CountDownLatch(1);
        CountDownLatch letTheSurvivorFinish = new CountDownLatch(1);
        JobHandler work = handler("work", context -> {
            if (context.attempt() == 1) {
                firstAttemptStarted.countDown();
                try {
                    letTheZombieFinish.await();
                } finally {
                    zombieFinished.countDown();
                }
            } else {
                secondAttemptStarted.countDown();
                letTheSurvivorFinish.await();
            }
        });
        Job job = enqueue("work");
        WorkerPool doomed = startWorker("doomed", 1, work);
        assertThat(firstAttemptStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // The process is gone as far as the database can tell: no heartbeats, nothing handed back.
        doomed.simulateCrash();
        WorkerPool survivor = startWorker("survivor", 1, work);
        assertThat(secondAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // The first worker was not dead after all, only cut off. While the second is still
        // working, it finishes and reports success.
        letTheZombieFinish.countDown();
        assertThat(zombieFinished.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);

        // Its report names attempt 1, which it no longer owns. The job is still the
        // survivor's, still running.
        Job contested = reload(job);
        assertThat(contested.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(contested.lockedBy()).isEqualTo(survivor.id());
        assertThat(contested.attempts()).isEqualTo(2);

        letTheSurvivorFinish.countDown();
        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.SUCCEEDED);
        List<JobAttempt> attempts = store.attempts(job.id());
        assertThat(attempts).extracting(JobAttempt::outcome).containsExactly("LEASE_EXPIRED", "SUCCEEDED");
        assertThat(attempts.get(0).worker()).isEqualTo(doomed.id());
        assertThat(attempts.get(1).worker()).isEqualTo(survivor.id());
    }

    @Test
    void aKilledWorkersJobsAreAllFinishedByAnother() throws Exception {
        CountDownLatch allStarted = new CountDownLatch(3);
        JobHandler work = handler("work", context -> {
            if (context.attempt() == 1) {
                allStarted.countDown();
                Thread.sleep(30_000);
            }
        });
        List<Job> jobs = List.of(enqueue("work"), enqueue("work"), enqueue("work"));
        WorkerPool doomed = startWorker("doomed", 3, work);
        assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();

        doomed.kill();
        // Dead, but the database does not know yet: the jobs still look like they are running.
        assertThat(count("RUNNING")).isEqualTo(3);
        WorkerPool survivor = startWorker("survivor", 3, work);

        await().atMost(SOON).until(() -> count("SUCCEEDED") == 3);
        for (Job job : jobs) {
            List<JobAttempt> attempts = store.attempts(job.id());
            assertThat(attempts).extracting(JobAttempt::outcome).containsExactly("LEASE_EXPIRED", "SUCCEEDED");
            assertThat(attempts.get(1).worker()).isEqualTo(survivor.id());
        }
    }

    @Test
    void losingTheLastAllowedAttemptToACrashMakesTheJobDeadNotStuck() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        JobHandler hang = handler("hang", context -> {
            started.countDown();
            Thread.sleep(60_000);
        });
        Job job = store.enqueue(NewJob.of(queue, "hang", "{}").withMaxAttempts(1)).job();
        WorkerPool doomed = startWorker("doomed", 1, hang);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        doomed.simulateCrash();

        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.DEAD);
        assertThat(reload(job).lastError()).contains(doomed.id()).contains("stopped responding");
        assertThat(reload(job).lockedBy()).isNull();
    }

    @Test
    void aJobLongerThanItsLeaseKeepsItByHeartbeating() {
        AtomicInteger runs = new AtomicInteger();
        // Three times the one-second lease.
        JobHandler slow = handler("slow", context -> {
            runs.incrementAndGet();
            Thread.sleep(3_000);
        });
        Job job = enqueue("slow");
        startWorker("steady", 1, slow);
        // A second worker stands ready to take the job the moment its lease lapses.
        startWorker("opportunist", 1, slow);

        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.SUCCEEDED);

        assertThat(runs).hasValue(1);
        assertThat(reload(job).attempts()).isEqualTo(1);
    }

    @Test
    void aWorkerThatLosesItsLeaseStopsWorkingOnTheJob() {
        AtomicBoolean interrupted = new AtomicBoolean();
        JobHandler work = handler("work", context -> {
            if (context.attempt() == 1) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                }
            }
        });
        Job job = enqueue("work");
        startWorker("solo", 1, work);
        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.RUNNING);

        // What a long pause looks like from outside: the lease runs out while the worker is
        // still alive. The reaper puts the job back in the queue.
        jdbc.sql("update jobs set lease_expires_at = now() - interval '1 second' where id = :id")
                .param("id", job.id())
                .update();

        // At its next heartbeat the worker learns the job is no longer its own and abandons it.
        await().atMost(SOON).untilTrue(interrupted);
        await().atMost(SOON).until(() -> reload(job).status() == JobStatus.SUCCEEDED);
        assertThat(reload(job).attempts()).isEqualTo(2);
    }

    @Test
    void shuttingDownHandsUnfinishedJobsBackWithoutSpendingAnAttempt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        JobHandler slow = handler("slow", context -> {
            if (runs.incrementAndGet() == 1) {
                started.countDown();
                Thread.sleep(30_000);
            }
        });
        Job job = enqueue("slow");
        WorkerPool leaving = startWorker("leaving", 1, slow);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // The grace period is 200ms; the job needs 30s.
        leaving.stop();

        Job handedBack = reload(job);
        assertThat(handedBack.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(handedBack.attempts()).isZero();
        assertThat(handedBack.lockedBy()).isNull();
        assertThat(store.attempts(job.id())).extracting(JobAttempt::outcome).containsExactly("RELEASED");

        // Available at once to the next worker, not after a lease has run out.
        startWorker("next", 1, slow);
        await().atMost(Duration.ofSeconds(2)).until(() -> reload(job).status() == JobStatus.SUCCEEDED);
        assertThat(reload(job).attempts()).isEqualTo(1);
    }

    @Test
    void aWorkerNeverHoldsMoreJobsThanItHasSlots() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        JobHandler measure = handler("measure", context -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            Thread.sleep(40);
            concurrent.decrementAndGet();
        });
        for (int i = 0; i < 40; i++) {
            enqueue("measure");
        }
        startWorker("bounded", 3, measure);

        Thread.sleep(150);
        // Mid-run: three in progress, and the rest still queued rather than hoarded.
        assertThat(count("RUNNING")).isLessThanOrEqualTo(3);
        await().atMost(SOON).until(() -> count("SUCCEEDED") == 40);
        assertThat(peak).hasValue(3);
    }
}

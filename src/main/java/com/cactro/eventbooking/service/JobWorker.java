package com.cactro.eventbooking.service;

import com.cactro.eventbooking.domain.Job;
import com.cactro.eventbooking.repo.JobRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DB-backed job queue worker.
 *  - poll(): claims PENDING jobs with FOR UPDATE SKIP LOCKED, flips them to PROCESSING, commits, hands them to a bounded pool.
 *  - failures: exponential backoff (5s, 10s, 20s ...), FAILED after max-attempts.
 *  - reaper: PROCESSING jobs older than 5 min (worker crashed) are returned to PENDING => at-least-once delivery.
 */
@Component
@Slf4j
public class JobWorker {
    private final JobRepository jobRepo;
    private final JobHandlers handlers;
    private final TransactionTemplate tx;
    private final ExecutorService pool;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final int maxInFlight;
    private final int maxAttempts;

    public JobWorker(JobRepository jobRepo, JobHandlers handlers, PlatformTransactionManager tm,
                     @Value("${app.worker.threads}") int threads,
                     @Value("${app.worker.max-in-flight}") int maxInFlight,
                     @Value("${app.worker.max-attempts}") int maxAttempts) {
        this.jobRepo = jobRepo;
        this.handlers = handlers;
        this.tx = new TransactionTemplate(tm);
        this.pool = Executors.newFixedThreadPool(threads);
        this.maxInFlight = maxInFlight;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-ms}")
    public void poll() {
        int free = maxInFlight - inFlight.get();
        if (free <= 0) return;
        List<Job> claimed = tx.execute(status -> {
            List<Job> jobs = jobRepo.claim(free);
            Instant now = Instant.now();
            for (Job j : jobs) {
                j.setStatus(Job.PROCESSING);
                j.setLockedAt(now);
                j.setAttempts(j.getAttempts() + 1);
            }
            return jobs;
        });
        if (claimed == null) return;
        for (Job j : claimed) {
            inFlight.incrementAndGet();
            pool.execute(() -> process(j));
        }
    }

    private void process(Job job) {
        try {
            handlers.handle(job);
            jobRepo.markDone(job.getId(), Instant.now());
        } catch (Exception ex) {
            String err = String.valueOf(ex.getMessage());
            if (err.length() > 500) err = err.substring(0, 500);
            if (job.getAttempts() >= maxAttempts) {
                log.error("Job {} ({}) FAILED permanently: {}", job.getId(), job.getType(), err);
                jobRepo.markFailed(job.getId(), Instant.now(), err);
            } else {
                long delaySec = (long) Math.min(300, 5 * Math.pow(2, job.getAttempts() - 1));
                log.warn("Job {} ({}) failed (attempt {}), retry in {}s: {}", job.getId(), job.getType(),
                        job.getAttempts(), delaySec, err);
                jobRepo.markRetry(job.getId(), Instant.now().plus(Duration.ofSeconds(delaySec)), err);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void reapStuck() {
        int n = jobRepo.resetStuck(Instant.now().minus(Duration.ofMinutes(5)));
        if (n > 0) log.warn("Reset {} stuck jobs to PENDING", n);
    }

    @PreDestroy
    void shutdown() { pool.shutdown(); }
}

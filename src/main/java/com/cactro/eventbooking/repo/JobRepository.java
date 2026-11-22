package com.cactro.eventbooking.repo;

import com.cactro.eventbooking.domain.Job;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    /** Claim a batch. SKIP LOCKED lets multiple workers/instances poll without blocking or double-processing. */
    @Query(value = "SELECT * FROM jobs WHERE status = 'PENDING' AND run_at <= now() " +
                   "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Job> claim(@Param("limit") int limit);

    @Modifying
    @Transactional
    @Query("update Job j set j.status = 'DONE', j.finishedAt = :now, j.lastError = null where j.id = :id")
    int markDone(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update Job j set j.status = 'PENDING', j.runAt = :runAt, j.lockedAt = null, j.lastError = :err where j.id = :id")
    int markRetry(@Param("id") Long id, @Param("runAt") Instant runAt, @Param("err") String err);

    @Modifying
    @Transactional
    @Query("update Job j set j.status = 'FAILED', j.finishedAt = :now, j.lastError = :err where j.id = :id")
    int markFailed(@Param("id") Long id, @Param("now") Instant now, @Param("err") String err);

    /** Crash recovery: jobs stuck in PROCESSING (worker died) go back to PENDING. */
    @Modifying
    @Transactional
    @Query("update Job j set j.status = 'PENDING', j.lockedAt = null where j.status = 'PROCESSING' and j.lockedAt < :cutoff")
    int resetStuck(@Param("cutoff") Instant cutoff);

    /** Fan-out in ONE SQL statement: one EVENT_UPDATE_EMAIL job per distinct booked customer. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO jobs (type, payload, status, attempts, run_at, created_at)
        SELECT 'EVENT_UPDATE_EMAIL',
               CAST(json_build_object('eventId', CAST(:eventId AS bigint),
                                      'customerId', b.customer_id,
                                      'summary', CAST(:summary AS text)) AS text),
               'PENDING', 0, now(), now()
        FROM (SELECT DISTINCT customer_id FROM bookings
              WHERE event_id = :eventId AND status = 'CONFIRMED') b
        """, nativeQuery = true)
    int fanOutEventUpdate(@Param("eventId") long eventId, @Param("summary") String summary);
}

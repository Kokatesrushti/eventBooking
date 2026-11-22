package com.cactro.eventbooking.repo;

import com.cactro.eventbooking.domain.Event;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByStatus(String status, Pageable pageable);

    List<Event> findByOrganizerIdOrderByStartTimeAsc(Long organizerId);

    /** Row lock; used by organizer update/cancel so they never overwrite available_seats with a stale value. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    /** Atomic seat decrement. Returns 0 rows if sold out / not enough seats / event not active. */
    @Modifying
    @Transactional
    @Query("update Event e set e.availableSeats = e.availableSeats - :qty " +
           "where e.id = :id and e.availableSeats >= :qty and e.status = 'ACTIVE'")
    int decrementSeats(@Param("id") Long id, @Param("qty") int qty);

    @Modifying
    @Transactional
    @Query("update Event e set e.availableSeats = e.availableSeats + :qty where e.id = :id")
    int incrementSeats(@Param("id") Long id, @Param("qty") int qty);
}

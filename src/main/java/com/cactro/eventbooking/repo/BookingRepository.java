package com.cactro.eventbooking.repo;

import com.cactro.eventbooking.domain.Booking;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String key);

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Booking> findByEventIdOrderByCreatedAtDesc(Long eventId);

    @Query("select distinct b.customerId from Booking b where b.eventId = :eventId and b.status = 'CONFIRMED'")
    List<Long> findCustomerIdsForEvent(@Param("eventId") Long eventId);

    @Modifying
    @Transactional
    @Query("update Booking b set b.emailSent = true where b.id = :id")
    int markEmailSent(@Param("id") Long id);
}

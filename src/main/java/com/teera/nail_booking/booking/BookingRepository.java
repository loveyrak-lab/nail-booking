package com.teera.nail_booking.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("select b from Booking b where b.status in ('CONFIRMED','BLOCKED') "
         + "and b.startTime < :to and b.endTime > :from")
    List<Booking> findActiveBetween(@Param("from") Instant from, @Param("to") Instant to);
}
package com.teera.nail_booking.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalonServiceRepository extends JpaRepository<SalonService, Long> {
    List<SalonService> findByActiveTrueOrderByIdAsc();
}
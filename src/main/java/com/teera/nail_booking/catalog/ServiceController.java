package com.teera.nail_booking.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public/services")
public class ServiceController {

    public record ServiceDto(Long id, String name, int basePriceThb, int baseMinutes) {}

    private final SalonServiceRepository repo;

    public ServiceController(SalonServiceRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ServiceDto> list() {
        return repo.findByActiveTrueOrderByIdAsc().stream()
            .map(s -> new ServiceDto(s.getId(), s.getName(), s.getBasePriceThb(), s.getBaseMinutes()))
            .toList();
    }
}
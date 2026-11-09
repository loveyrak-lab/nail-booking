package com.teera.nail_booking.availability;

import com.teera.nail_booking.booking.Booking;
import com.teera.nail_booking.booking.BookingRepository;
import com.teera.nail_booking.catalog.SalonService;
import com.teera.nail_booking.catalog.SalonServiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class AvailabilityService {

    public record SlotDto(String start, String end, boolean available) {}
    public record AvailabilityResponse(LocalDate date, int durationMinutes, List<SlotDto> slots) {}

    private static final int[] LEVEL_ADD_MINUTES = {0, 30, 60};

    private final SalonServiceRepository services;
    private final BookingRepository bookings;
    private final Clock clock;
    private final LocalTime openTime;
    private final LocalTime closeTime;
    private final LocalTime lastStartTime;
    private final int slotMinutes;
    private final int bufferMinutes;
    private final int leadMinutes;
    private final int maxDaysAhead;

    public AvailabilityService(SalonServiceRepository services,
                               BookingRepository bookings,
                               Clock clock,
                               @Value("${salon.open-time}") String openTime,
                               @Value("${salon.close-time}") String closeTime,
                               @Value("${salon.last-start-time}") String lastStartTime,
                               @Value("${salon.slot-minutes}") int slotMinutes,
                               @Value("${salon.buffer-minutes}") int bufferMinutes,
                               @Value("${salon.lead-minutes}") int leadMinutes,
                               @Value("${salon.max-days-ahead}") int maxDaysAhead) {
        this.services = services;
        this.bookings = bookings;
        this.clock = clock;
        this.openTime = LocalTime.parse(openTime);
        this.closeTime = LocalTime.parse(closeTime);
        this.lastStartTime = LocalTime.parse(lastStartTime);
        this.slotMinutes = slotMinutes;
        this.bufferMinutes = bufferMinutes;
        this.leadMinutes = leadMinutes;
        this.maxDaysAhead = maxDaysAhead;
    }

    public AvailabilityResponse find(long serviceId, LocalDate date, int level) {
        SalonService svc = services.findById(serviceId)
            .filter(SalonService::isActive)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        if (level < 0 || level >= LEVEL_ADD_MINUTES.length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid level");
        }
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(maxDaysAhead))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date out of range");
        }

        int duration = svc.getBaseMinutes() + (svc.isAllowsArtLevel() ? LEVEL_ADD_MINUTES[level] : 0);
        ZoneId zone = clock.getZone();
        List<Booking> busy = bookings.findActiveBetween(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant());
        LocalDateTime earliest = LocalDateTime.now(clock).plusMinutes(leadMinutes);
        LocalDateTime closing = date.atTime(closeTime);

        List<SlotDto> slots = new ArrayList<>();
        for (LocalTime t = openTime; !t.isAfter(lastStartTime); t = t.plusMinutes(slotMinutes)) {
            LocalDateTime start = date.atTime(t);
            LocalDateTime end = start.plusMinutes(duration);
            boolean ok = !end.isAfter(closing) && !start.isBefore(earliest);

            if (ok) {
                Instant s = start.atZone(zone).toInstant();
                Instant eWithBuffer = end.atZone(zone).toInstant().plus(bufferMinutes, ChronoUnit.MINUTES);
                for (Booking b : busy) {
                    Instant bEndWithBuffer = b.getEndTime().plus(bufferMinutes, ChronoUnit.MINUTES);
                    if (s.isBefore(bEndWithBuffer) && eWithBuffer.isAfter(b.getStartTime())) {
                        ok = false;
                        break;
                    }
                }
            }
            slots.add(new SlotDto(t.toString(), end.toLocalTime().toString(), ok));
        }
        return new AvailabilityResponse(date, duration, slots);
    }
}
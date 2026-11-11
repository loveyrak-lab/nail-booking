package com.teera.nail_booking.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.teera.nail_booking.reservation.ReservationService.BadRequest;
import com.teera.nail_booking.reservation.ReservationService.MenuDto;
import com.teera.nail_booking.reservation.ReservationService.SlotDto;
import com.teera.nail_booking.reservation.ReservationService.SlotsDto;

/**
 * Unit tests for the booking rules (price, duration, closing time, lead time,
 * buffer, exclusions). No Spring and no database: the JdbcTemplate is replaced
 * by a tiny fake that returns a fixed menu and a fixed list of busy ranges.
 *
 * "Now" is fixed at 2026-12-01 10:00 Asia/Bangkok.
 * Shop settings match the defaults: open 09:00, close 22:00, last start 21:30,
 * 30-minute slots, 15-minute buffer, 30-minute lead time, 30 days ahead.
 */
class ReservationServiceSlotsTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-12-01T03:00:00Z"), BANGKOK);

    private static final String TODAY = "2026-12-01";
    private static final String TOMORROW = "2026-12-02";

    // Small menu used only by these tests
    private static final List<MenuDto> MENU = List.of(
            new MenuDto("gel-h", "main", "Hand gel", "Hand Gel", 299, false, 60, List.of("gel-hf")),
            new MenuDto("gel-hf", "main", "Hand and feet gel", "Hand & Feet Gel", 598, false, 120, List.of("gel-h", "gel-f")),
            new MenuDto("gel-f", "main", "Feet gel", "Feet Gel", 299, false, 60, List.of("gel-hf")),
            new MenuDto("paint", "art", "Paint art", "Paint Art", 30, true, 15, List.of()));

    /** Replaces the two JDBC calls the slot logic uses. */
    private static class FakeJdbc extends JdbcTemplate {
        private final List<OffsetDateTime[]> busy;

        FakeJdbc(List<OffsetDateTime[]> busy) {
            this.busy = busy;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return (List<T>) MENU; // menu()
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return (List<T>) busy; // busyOn(date)
        }
    }

    private static ReservationService service(List<OffsetDateTime[]> busy) {
        return new ReservationService(new FakeJdbc(busy), NOW,
                "09:00", "22:00", "21:30", 30, 15, 30, 30);
    }

    private static ReservationService service() {
        return service(List.of());
    }

    private static Map<String, Boolean> availability(SlotsDto dto) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (SlotDto s : dto.slots()) map.put(s.start(), s.available());
        return map;
    }

    private static OffsetDateTime at(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+07:00");
    }

    // ---------- price and duration ----------

    @Test
    void sumsDurationAndPriceAndFlagsFromPrice() {
        SlotsDto dto = service().slots(List.of("gel-h", "paint"), TOMORROW);

        assertEquals(75, dto.durationMinutes());   // 60 + 15
        assertEquals(329, dto.priceThb());         // 299 + 30
        assertTrue(dto.priceFrom(), "paint art is a 'from' price, so the total is too");
    }

    @Test
    void fixedPriceItemsAreNotFlaggedAsFromPrice() {
        SlotsDto dto = service().slots(List.of("gel-h"), TOMORROW);

        assertFalse(dto.priceFrom());
    }

    // ---------- input rules ----------

    @Test
    void mutuallyExclusiveItemsAreRejected() {
        assertThrows(BadRequest.class, () -> service().slots(List.of("gel-h", "gel-hf"), TOMORROW));
    }

    @Test
    void unknownItemIsRejected() {
        assertThrows(BadRequest.class, () -> service().slots(List.of("does-not-exist"), TOMORROW));
    }

    @Test
    void emptySelectionIsRejected() {
        assertThrows(BadRequest.class, () -> service().slots(List.of(), TOMORROW));
    }

    @Test
    void pastDateIsRejected() {
        assertThrows(BadRequest.class, () -> service().slots(List.of("gel-h"), "2026-11-30"));
    }

    @Test
    void dateBeyondThirtyDaysIsRejected() {
        // today + 30 days is the last allowed day
        assertTrue(service().slots(List.of("gel-h"), "2026-12-31").slots().size() > 0);
        assertThrows(BadRequest.class, () -> service().slots(List.of("gel-h"), "2027-01-01"));
    }

    @Test
    void malformedDateIsRejected() {
        assertThrows(BadRequest.class, () -> service().slots(List.of("gel-h"), "01/12/2026"));
    }

    // ---------- closing time ----------

    @Test
    void jobMustFinishByClosingTime() {
        Map<String, Boolean> a = availability(service().slots(List.of("gel-h"), TOMORROW)); // 60 min

        assertTrue(a.get("21:00"), "21:00 + 60 min ends exactly at 22:00, which is allowed");
        assertFalse(a.get("21:30"), "21:30 + 60 min would end after closing");
    }

    @Test
    void longerJobsLoseTheLateSlots() {
        Map<String, Boolean> a = availability(service().slots(List.of("gel-hf"), TOMORROW)); // 120 min

        assertTrue(a.get("20:00"));
        assertFalse(a.get("20:30"));
        assertFalse(a.get("21:30"));
    }

    @Test
    void slotListRunsFromOpeningToLastStart() {
        List<SlotDto> slots = service().slots(List.of("paint"), TOMORROW).slots();

        assertEquals("09:00", slots.get(0).start());
        assertEquals("21:30", slots.get(slots.size() - 1).start());
    }

    // ---------- lead time ----------

    @Test
    void slotsInsideTheLeadTimeAreUnavailableToday() {
        // now = 10:00, lead = 30 min, so the earliest bookable start is 10:30
        Map<String, Boolean> a = availability(service().slots(List.of("gel-h"), TODAY));

        assertFalse(a.get("09:00"));
        assertFalse(a.get("10:00"));
        assertTrue(a.get("10:30"));
    }

    // ---------- overlap and buffer ----------

    @Test
    void bufferAfterAnExistingBookingBlocksTheNextSlot() {
        // Existing booking 10:00-11:00 is stored with its 15-minute buffer: end_time 11:15
        List<OffsetDateTime[]> busy = List.<OffsetDateTime[]>of(
                new OffsetDateTime[] { at("2026-12-02T10:00:00"), at("2026-12-02T11:15:00") });

        Map<String, Boolean> a = availability(service(busy).slots(List.of("gel-h"), TOMORROW)); // 60 min

        assertTrue(a.get("08:00") == null, "no slot before opening");
        assertFalse(a.get("09:00"), "09:00-10:00 plus 15-minute buffer runs into the 10:00 booking");
        assertFalse(a.get("10:30"), "overlaps the existing booking");
        assertFalse(a.get("11:00"), "starts before the existing booking's buffer ends at 11:15");
        assertTrue(a.get("11:30"), "first free slot after the buffer");
    }

    @Test
    void shortJobFitsBeforeAnExistingBookingWhenBufferAllows() {
        List<OffsetDateTime[]> busy = List.<OffsetDateTime[]>of(
                new OffsetDateTime[] { at("2026-12-02T10:00:00"), at("2026-12-02T11:15:00") });

        // 15-minute job at 09:00 ends 09:15; with buffer 09:30, which is before 10:00
        Map<String, Boolean> a = availability(service(busy).slots(List.of("paint"), TOMORROW));

        assertTrue(a.get("09:00"));
        assertTrue(a.get("09:30"));
        assertFalse(a.get("10:00"));
    }

    @Test
    void staffBlockWithoutBufferStillBlocksItsOwnRange() {
        // Lunch break 12:00-13:00
        List<OffsetDateTime[]> busy = List.<OffsetDateTime[]>of(
                new OffsetDateTime[] { at("2026-12-02T12:00:00"), at("2026-12-02T13:00:00") });

        Map<String, Boolean> a = availability(service(busy).slots(List.of("paint"), TOMORROW));

        assertFalse(a.get("12:00"));
        assertFalse(a.get("12:30"));
        assertTrue(a.get("13:00"));
    }
}
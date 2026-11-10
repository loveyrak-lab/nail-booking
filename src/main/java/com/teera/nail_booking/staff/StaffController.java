package com.teera.nail_booking.staff;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API สำหรับช่าง: ทุกเส้นทางใต้ /api/staff ต้องล็อกอิน (SecurityConfig: anyRequest().authenticated()) */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    public record Row(long id, String date, String start, String end, String status,
                      String name, String phone, String lineId, List<String> items,
                      Integer priceThb, boolean priceFrom, String note, List<Long> photos) {}

    public record BlockRequest(String date, String start, String end, String note) {}

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StaffController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** ตัวอย่าง: /api/staff/bookings?from=2026-11-10&days=7 (ไม่ใส่ = วันนี้ 7 วัน) */
    @GetMapping("/bookings")
    public ResponseEntity<?> list(@RequestParam(required = false) String from,
                                  @RequestParam(defaultValue = "7") int days) {
        LocalDate start;
        try {
            start = (from == null || from.isBlank()) ? LocalDate.now(clock) : LocalDate.parse(from);
        } catch (DateTimeException e) {
            return error(HttpStatus.BAD_REQUEST, "วันที่ไม่ถูกต้อง");
        }
        int span = Math.max(1, Math.min(days, 31));
        ZoneId zone = clock.getZone();
        OffsetDateTime a = start.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime b = start.plusDays(span).atStartOfDay(zone).toOffsetDateTime();

        Map<String, String> names = new HashMap<>();
        for (String[] pair : jdbc.query("SELECT code, name FROM menu_item",
                (rs, i) -> new String[]{rs.getString("code"), rs.getString("name")})) {
            names.put(pair[0], pair[1]);
        }

        Map<Long, List<Long>> photos = new HashMap<>();
        jdbc.query("""
                SELECT p.booking_id, p.id FROM booking_photo p JOIN booking b ON b.id = p.booking_id
                WHERE b.start_time >= ? AND b.start_time < ? ORDER BY p.id
                """, (rs, i) -> {
            photos.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
            return 0;
        }, a, b);

        List<Row> rows = jdbc.query("""
                SELECT b.id, b.start_time, b.end_time, b.status, b.note, b.items,
                       b.total_minutes, b.total_price_thb, b.price_is_from,
                       c.nickname, c.phone, c.line_id
                FROM booking b LEFT JOIN customer c ON c.id = b.customer_id
                WHERE b.status IN ('CONFIRMED', 'BLOCKED') AND b.start_time >= ? AND b.start_time < ?
                ORDER BY b.start_time
                """, (rs, i) -> {
            ZonedDateTime s = rs.getObject("start_time", OffsetDateTime.class).atZoneSameInstant(zone);
            ZonedDateTime e = rs.getObject("end_time", OffsetDateTime.class).atZoneSameInstant(zone);
            int total = rs.getInt("total_minutes");
            boolean hasTotal = !rs.wasNull();
            String status = rs.getString("status");
            if (hasTotal && "CONFIRMED".equals(status)) e = s.plusMinutes(total);
            int price = rs.getInt("total_price_thb");
            Integer priceObj = rs.wasNull() ? null : price;
            List<String> items = new ArrayList<>();
            String raw = rs.getString("items");
            if (raw != null && !raw.isBlank())
                for (String code : raw.split(",")) items.add(names.getOrDefault(code.trim(), code.trim()));
            return new Row(rs.getLong("id"), s.toLocalDate().toString(), s.format(HHMM), e.format(HHMM),
                    status, rs.getString("nickname"), rs.getString("phone"), rs.getString("line_id"),
                    items, priceObj, rs.getBoolean("price_is_from"), rs.getString("note"),
                    photos.getOrDefault(rs.getLong("id"), List.of()));
        }, a, b);
        return ResponseEntity.ok(rows);
    }

    /** รูปเรฟของลูกค้า (ต้องล็อกอินเป็นช่าง) */
    @GetMapping("/photos/{id}")
    public ResponseEntity<?> photo(@PathVariable long id) {
        List<byte[]> found = jdbc.query("SELECT data FROM booking_photo WHERE id = ?", (rs, i) -> rs.getBytes(1), id);
        if (found.isEmpty()) return error(HttpStatus.NOT_FOUND, "ไม่พบรูป");
        return ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .header("Cache-Control", "private, max-age=3600")
                .body(found.get(0));
    }

    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long id) {
        int n = jdbc.update("UPDATE booking SET status = 'CANCELLED' WHERE id = ? AND status IN ('CONFIRMED', 'BLOCKED')", id);
        if (n == 0) return error(HttpStatus.NOT_FOUND, "ไม่พบคิวนี้ หรือถูกยกเลิกไปแล้ว");
        return ResponseEntity.ok(Map.of("ok", "true"));
    }

    /** ปิดเวลา เช่น พักกลางวัน / วันหยุด */
    @PostMapping("/blocks")
    public ResponseEntity<?> block(@RequestBody BlockRequest r) {
        ZonedDateTime s;
        ZonedDateTime e;
        try {
            LocalDate d = LocalDate.parse(r.date());
            s = ZonedDateTime.of(d, LocalTime.parse(r.start()), clock.getZone());
            e = ZonedDateTime.of(d, LocalTime.parse(r.end()), clock.getZone());
        } catch (DateTimeException | NullPointerException ex) {
            return error(HttpStatus.BAD_REQUEST, "วันที่หรือเวลาไม่ถูกต้อง");
        }
        if (!e.isAfter(s)) return error(HttpStatus.BAD_REQUEST, "เวลาจบต้องหลังเวลาเริ่ม");
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        if (note != null && note.length() > 100) note = note.substring(0, 100);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO booking (customer_id, service_id, start_time, end_time, status, source, note)
                    VALUES (NULL, NULL, ?, ?, 'BLOCKED', 'STAFF', ?) RETURNING id
                    """, Long.class, s.toOffsetDateTime(), e.toOffsetDateTime(), note);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (DataIntegrityViolationException ex) {
            if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains("no_overlap"))
                return error(HttpStatus.CONFLICT, "ช่วงนี้มีคิวอยู่แล้ว ยกเลิกคิวนั้นก่อนจึงจะปิดเวลาได้");
            throw ex;
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

package com.teera.nail_booking.reservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    public record MenuDto(String code, String category, String name, String nameEn,
                          int priceThb, boolean priceFrom, int minutes, List<String> excludes) {}

    public record SlotDto(String start, boolean available) {}

    public record SlotsDto(String date, int durationMinutes, int priceThb, boolean priceFrom,
                           List<SlotDto> slots) {}

    public record BookingRequest(List<String> items, String date, String start,
                                 String name, String phone, String lineId, String note) {}

    public record BookingResult(long id, String date, String start, String end,
                                int durationMinutes, int priceThb, boolean priceFrom,
                                List<String> items, String photoToken) {}

    /** ข้อมูลที่ลูกค้าส่งมาไม่ถูกต้อง -> HTTP 400 */
    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) { super(message); }
    }

    /** ช่วงเวลานี้ถูกจองไปแล้ว -> HTTP 409 */
    public static class SlotTaken extends RuntimeException {
        public SlotTaken(String message) { super(message); }
    }

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LocalTime open;
    private final LocalTime close;
    private final LocalTime lastStart;
    private final int slotMinutes;
    private final int bufferMinutes;
    private final int leadMinutes;
    private final int maxDaysAhead;

    public ReservationService(JdbcTemplate jdbc, Clock clock,
                              @Value("${salon.open-time:09:00}") String open,
                              @Value("${salon.close-time:22:00}") String close,
                              @Value("${salon.last-start-time:21:30}") String lastStart,
                              @Value("${salon.slot-minutes:30}") int slotMinutes,
                              @Value("${salon.buffer-minutes:15}") int bufferMinutes,
                              @Value("${salon.lead-minutes:30}") int leadMinutes,
                              @Value("${salon.max-days-ahead:30}") int maxDaysAhead) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.open = LocalTime.parse(open);
        this.close = LocalTime.parse(close);
        this.lastStart = LocalTime.parse(lastStart);
        this.slotMinutes = slotMinutes;
        this.bufferMinutes = bufferMinutes;
        this.leadMinutes = leadMinutes;
        this.maxDaysAhead = maxDaysAhead;
    }

    // ---------- เมนู ----------

    public List<MenuDto> menu() {
        return jdbc.query("""
                SELECT code, category, name, name_en, price_thb, price_is_from, minutes, excludes
                FROM menu_item WHERE active ORDER BY
                  CASE category WHEN 'main' THEN 1 WHEN 'ext' THEN 2 ELSE 3 END, sort_order
                """,
                (rs, i) -> new MenuDto(
                        rs.getString("code"), rs.getString("category"),
                        rs.getString("name"), rs.getString("name_en"),
                        rs.getInt("price_thb"), rs.getBoolean("price_is_from"),
                        rs.getInt("minutes"), splitCodes(rs.getString("excludes"))));
    }

    private static List<String> splitCodes(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private List<MenuDto> resolve(List<String> codes) {
        if (codes == null || codes.isEmpty()) throw new BadRequest("กรุณาเลือกบริการอย่างน้อย 1 อย่าง");
        Set<String> wanted = new LinkedHashSet<>();
        for (String c : codes) if (c != null && !c.isBlank()) wanted.add(c.trim());
        if (wanted.isEmpty() || wanted.size() > 15) throw new BadRequest("รายการบริการไม่ถูกต้อง");
        Map<String, MenuDto> all = new LinkedHashMap<>();
        for (MenuDto m : menu()) all.put(m.code(), m);
        List<MenuDto> picked = new ArrayList<>();
        for (String c : wanted) {
            MenuDto m = all.get(c);
            if (m == null) throw new BadRequest("ไม่พบบริการ: " + c);
            picked.add(m);
        }
        for (MenuDto m : picked)
            for (String ex : m.excludes())
                if (wanted.contains(ex)) throw new BadRequest("เลือก \"" + m.name() + "\" ร่วมกับบางรายการไม่ได้");
        return picked;
    }

    // ---------- เวลาว่าง ----------

    public SlotsDto slots(List<String> codes, String dateText) {
        List<MenuDto> items = resolve(codes);
        LocalDate date = parseDate(dateText);
        int duration = items.stream().mapToInt(MenuDto::minutes).sum();
        List<OffsetDateTime[]> busy = busyOn(date);

        List<SlotDto> out = new ArrayList<>();
        for (LocalTime t = open; !t.isAfter(lastStart); t = t.plusMinutes(slotMinutes)) {
            out.add(new SlotDto(t.format(HHMM), isFree(date, t, duration, busy)));
        }
        return new SlotsDto(date.toString(), duration,
                items.stream().mapToInt(MenuDto::priceThb).sum(),
                items.stream().anyMatch(MenuDto::priceFrom), out);
    }

    private LocalDate parseDate(String text) {
        LocalDate d;
        try {
            d = LocalDate.parse(text);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequest("วันที่ไม่ถูกต้อง");
        }
        LocalDate today = LocalDate.now(clock);
        if (d.isBefore(today) || d.isAfter(today.plusDays(maxDaysAhead)))
            throw new BadRequest("จองได้ล่วงหน้าไม่เกิน " + maxDaysAhead + " วัน");
        return d;
    }

    private ZonedDateTime at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, clock.getZone());
    }

    /** ช่วงที่ถูกจอง/บล็อกอยู่ในวันนั้น (end_time รวม buffer แล้ว) */
    private List<OffsetDateTime[]> busyOn(LocalDate date) {
        OffsetDateTime dayStart = date.atStartOfDay(clock.getZone()).toOffsetDateTime();
        OffsetDateTime dayEnd = date.plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime();
        return jdbc.query("""
                SELECT start_time, end_time FROM booking
                WHERE status IN ('CONFIRMED', 'BLOCKED') AND start_time < ? AND end_time > ?
                """,
                (rs, i) -> new OffsetDateTime[]{
                        rs.getObject("start_time", OffsetDateTime.class),
                        rs.getObject("end_time", OffsetDateTime.class)},
                dayEnd, dayStart);
    }

    private boolean isFree(LocalDate date, LocalTime start, int duration, List<OffsetDateTime[]> busy) {
        ZonedDateTime s = at(date, start);
        ZonedDateTime workEnd = s.plusMinutes(duration);
        ZonedDateTime blockEnd = s.plusMinutes(duration + bufferMinutes);
        if (workEnd.isAfter(at(date, close))) return false;
        if (s.isBefore(ZonedDateTime.now(clock).plusMinutes(leadMinutes))) return false;
        for (OffsetDateTime[] b : busy) {
            if (s.toOffsetDateTime().isBefore(b[1]) && blockEnd.toOffsetDateTime().isAfter(b[0])) return false;
        }
        return true;
    }

    // ---------- จองคิว ----------

    @Transactional
    public BookingResult create(BookingRequest r) {
        if (r == null) throw new BadRequest("ข้อมูลไม่ครบ");
        List<MenuDto> items = resolve(r.items());
        LocalDate date = parseDate(r.date());

        LocalTime start;
        try {
            start = LocalTime.parse(r.start());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequest("เวลาไม่ถูกต้อง");
        }
        long offset = java.time.Duration.between(open, start).toMinutes();
        if (start.isBefore(open) || start.isAfter(lastStart) || offset % slotMinutes != 0)
            throw new BadRequest("เวลานี้ไม่อยู่ในช่วงที่เปิดจอง");

        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty() || name.length() > 40) throw new BadRequest("กรุณากรอกชื่อเล่น");
        String phone = r.phone() == null ? "" : r.phone().replaceAll("\\D", "");
        if (!phone.matches("^0[0-9]{9}$")) throw new BadRequest("เบอร์โทรต้องเป็นเบอร์มือถือ 10 หลัก");
        String line = r.lineId() == null || r.lineId().isBlank() ? null : r.lineId().trim();
        if (line != null && line.length() > 50) throw new BadRequest("LINE ID ยาวเกินไป");
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        if (note != null && note.length() > 300) throw new BadRequest("ข้อความถึงช่างยาวเกินไป");

        int duration = items.stream().mapToInt(MenuDto::minutes).sum();
        int price = items.stream().mapToInt(MenuDto::priceThb).sum();
        boolean from = items.stream().anyMatch(MenuDto::priceFrom);

        if (!isFree(date, start, duration, busyOn(date)))
            throw new SlotTaken("ช่วงเวลานี้เพิ่งถูกจองไปแล้ว กรุณาเลือกเวลาอื่น");

        ZonedDateTime s = at(date, start);
        OffsetDateTime startAt = s.toOffsetDateTime();
        OffsetDateTime blockEnd = s.plusMinutes(duration + bufferMinutes).toOffsetDateTime();
        String codes = String.join(",", items.stream().map(MenuDto::code).toList());
        String photoToken = java.util.UUID.randomUUID().toString();

        try {
            Long customerId = jdbc.queryForObject("""
                    INSERT INTO customer (nickname, phone, line_id) VALUES (?, ?, ?)
                    ON CONFLICT (phone) DO UPDATE
                      SET nickname = EXCLUDED.nickname,
                          line_id  = COALESCE(EXCLUDED.line_id, customer.line_id)
                    RETURNING id
                    """, Long.class, name, phone, line);

            Long bookingId = jdbc.queryForObject("""
                    INSERT INTO booking (customer_id, service_id, start_time, end_time, status, source,
                                         note, items, total_minutes, total_price_thb, price_is_from, photo_token)
                    VALUES (?, NULL, ?, ?, 'CONFIRMED', 'WEB', ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, customerId, startAt, blockEnd, note, codes, duration, price, from, photoToken);

            return new BookingResult(bookingId, date.toString(), start.format(HHMM),
                    start.plusMinutes(duration).format(HHMM), duration, price, from,
                    items.stream().map(MenuDto::name).toList(), photoToken);
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("no_overlap"))
                throw new SlotTaken("ช่วงเวลานี้เพิ่งถูกจองไปแล้ว กรุณาเลือกเวลาอื่น");
            throw e;
        }
    }

    // ---------- รูปเรฟ ----------

    private static final int MAX_PHOTO_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PHOTOS = 5;

    /** รับรูป JPEG ที่ลูกค้าแนบ (ต้องมี token ที่ได้ตอนจองสำเร็จ) */
    @Transactional
    public void addPhoto(long bookingId, String token, byte[] data) {
        if (token == null || token.isBlank()) throw new BadRequest("ไม่มีสิทธิ์แนบรูป");
        Integer ok = jdbc.queryForObject(
                "SELECT count(*) FROM booking WHERE id = ? AND photo_token = ? AND status = 'CONFIRMED'",
                Integer.class, bookingId, token);
        if (ok == null || ok == 0) throw new BadRequest("ไม่มีสิทธิ์แนบรูปให้คิวนี้");
        if (data == null || data.length == 0 || data.length > MAX_PHOTO_BYTES)
            throw new BadRequest("ไฟล์รูปต้องไม่เกิน 2 MB");
        boolean jpeg = data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
        if (!jpeg) throw new BadRequest("รองรับเฉพาะรูป JPEG");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM booking_photo WHERE booking_id = ?", Integer.class, bookingId);
        if (n != null && n >= MAX_PHOTOS) throw new BadRequest("แนบรูปได้สูงสุด " + MAX_PHOTOS + " รูป");
        jdbc.update("INSERT INTO booking_photo (booking_id, data) VALUES (?, ?)", bookingId, data);
    }
}

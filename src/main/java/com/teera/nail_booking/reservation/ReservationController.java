package com.teera.nail_booking.reservation;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @GetMapping("/menu")
    public List<ReservationService.MenuDto> menu() {
        return service.menu();
    }

    /** ตัวอย่าง: /api/public/slots?items=gel-h,spa-1&date=2026-11-10 */
    @GetMapping("/slots")
    public ResponseEntity<?> slots(@RequestParam List<String> items, @RequestParam String date) {
        try {
            return ResponseEntity.ok(service.slots(items, date));
        } catch (ReservationService.BadRequest e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> book(@RequestBody ReservationService.BookingRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
        } catch (ReservationService.BadRequest e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ReservationService.SlotTaken e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** แนบรูปเรฟ: ส่งไฟล์ JPEG เป็น body ตรงๆ พร้อม ?token= ที่ได้ตอนจอง */
    @PostMapping("/bookings/{id}/photos")
    public ResponseEntity<?> photo(@PathVariable long id, @RequestParam String token, @RequestBody byte[] data) {
        try {
            service.addPhoto(id, token, data);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", "true"));
        } catch (ReservationService.BadRequest e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
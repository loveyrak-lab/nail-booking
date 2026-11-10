-- รูปเรฟที่ลูกค้าแนบตอนจองคิว (เก็บในฐานข้อมูล ย่อรูปแล้วที่ฝั่งลูกค้า)
ALTER TABLE booking ADD COLUMN IF NOT EXISTS photo_token TEXT;

CREATE TABLE IF NOT EXISTS booking_photo (
    id         BIGSERIAL PRIMARY KEY,
    booking_id BIGINT      NOT NULL REFERENCES booking (id) ON DELETE CASCADE,
    data       BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS booking_photo_booking_idx ON booking_photo (booking_id);

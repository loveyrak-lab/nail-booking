-- เมนูร้าน Perfect Nails (ราคาตามเมนูจริง, minutes = ค่าประมาณ แก้ได้ภายหลัง)
CREATE TABLE menu_item (
    code          TEXT PRIMARY KEY,
    category      TEXT    NOT NULL CHECK (category IN ('main', 'ext', 'art')),
    name          TEXT    NOT NULL,
    name_en       TEXT,
    price_thb     INT     NOT NULL CHECK (price_thb >= 0),
    price_is_from BOOLEAN NOT NULL DEFAULT FALSE,
    minutes       INT     NOT NULL CHECK (minutes BETWEEN 5 AND 600),
    excludes      TEXT    NOT NULL DEFAULT '',
    sort_order    INT     NOT NULL,
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO menu_item (code, category, name, name_en, price_thb, price_is_from, minutes, excludes, sort_order) VALUES
 ('gel-h',   'main', 'ทาสีเจลมือ',            'Hand Gel',              299, FALSE,  60, 'gel-hf',        1),
 ('gel-f',   'main', 'ทาสีเจลเท้า',           'Feet Gel',              299, FALSE,  60, 'gel-hf',        2),
 ('gel-hf',  'main', 'ทาสีเจลมือ + เท้า',     'Hand & Feet Gel',       598, FALSE, 120, 'gel-h,gel-f',   3),
 ('overlay', 'main', 'เสริมหน้าเล็บ',         'Overlay',               350, FALSE,  45, '',              4),
 ('cut-hf',  'main', 'ตัดหนังมือ + เท้า',     'Pedicure and Manicure', 400, FALSE,  60, 'cut-1',         5),
 ('cut-1',   'main', 'ตัดหนัง มือ หรือ เท้า', 'เลือก 1 อย่าง',         219, FALSE,  30, 'cut-hf',        6),
 ('spa-hf',  'main', 'สปามือ + เท้า',         'Hand & Feet Spa',       799, FALSE, 105, 'spa-1',         7),
 ('spa-1',   'main', 'สปา มือ หรือ เท้า',     'เลือก 1 อย่าง',         499, FALSE,  60, 'spa-hf',        8),
 ('ext-gel', 'ext',  'ต่อเล็บเจล',            'Gel Extensions',        699, FALSE,  90, 'ext-pvc',       1),
 ('ext-pvc', 'ext',  'ต่อเล็บ PVC',           'PVC Extensions',        399, FALSE,  60, 'ext-gel',       2),
 ('fill',    'ext',  'เติมโคนเจล',            'Gel Filling',           359, FALSE,  60, '',              3),
 ('pvc-rm',  'ext',  'ถอด PVC (ต่อจากร้านอื่น)', 'PVC Remove',         100, FALSE,  15, '',              4),
 ('french',  'art',  'เฟรนปลาย',              'French tip',            200, FALSE,  30, '',              1),
 ('paint',   'art',  'เพ้นท์ / อะไหล่',        'Nail art',               30, TRUE,   30, '',              2),
 ('ombre',   'art',  'ออมเบรค / ไล่สี',        'Ombre',                 200, FALSE,  30, '',              3),
 ('buff',    'art',  'ขัดผง',                 'Buff',                  150, FALSE,  20, '',              4);

-- คิวหนึ่งรายการมีได้หลายเมนู: ไม่ผูกกับ service เดียวอีกต่อไป
ALTER TABLE booking ALTER COLUMN service_id DROP NOT NULL;
ALTER TABLE booking ADD COLUMN items           TEXT    NOT NULL DEFAULT '';
ALTER TABLE booking ADD COLUMN total_minutes   INT;
ALTER TABLE booking ADD COLUMN total_price_thb INT;
ALTER TABLE booking ADD COLUMN price_is_from   BOOLEAN NOT NULL DEFAULT FALSE;

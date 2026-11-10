-- ให้ช่าง "ปิดเวลา" (พัก/วันหยุด) ได้ โดยไม่ต้องมีลูกค้าผูกอยู่
ALTER TABLE booking ALTER COLUMN customer_id DROP NOT NULL;

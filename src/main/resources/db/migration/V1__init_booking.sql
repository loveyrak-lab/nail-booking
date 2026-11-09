CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE service (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  base_price_thb INT NOT NULL,
  base_minutes INT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE customer (
  id BIGSERIAL PRIMARY KEY,
  nickname VARCHAR(50) NOT NULL,
  phone VARCHAR(10) NOT NULL UNIQUE,
  line_id VARCHAR(50),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_phone CHECK (phone ~ '^0[0-9]{9}$')
);

CREATE TABLE booking (
  id BIGSERIAL PRIMARY KEY,
  customer_id BIGINT NOT NULL REFERENCES customer(id),
  service_id BIGINT NOT NULL REFERENCES service(id),
  start_time TIMESTAMPTZ NOT NULL,
  end_time TIMESTAMPTZ NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
  source VARCHAR(10) NOT NULL DEFAULT 'ONLINE',
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_time CHECK (end_time > start_time),
  CONSTRAINT chk_status CHECK (status IN ('CONFIRMED','CANCELLED','BLOCKED')),
  CONSTRAINT no_overlap EXCLUDE USING gist (
    tstzrange(start_time, end_time) WITH &&
  ) WHERE (status IN ('CONFIRMED','BLOCKED'))
);

INSERT INTO service (name, base_price_thb, base_minutes) VALUES
  ('ทาสีเจลมือ', 299, 60),
  ('ทาสีเจลเท้า', 299, 60),
  ('สปามือ', 0, 45),
  ('สปาเท้า', 0, 60),
  ('ครบวงจร', 0, 150);
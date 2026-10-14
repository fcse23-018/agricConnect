-- ============================================================================
-- AgriConnect · 003_marketplace.sql
-- Produce listings and buyer orders (transactional stock consistency).
-- ============================================================================
BEGIN;

CREATE TABLE produce_listings (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  produce         text NOT NULL CHECK (char_length(btrim(produce)) BETWEEN 2 AND 120),
  description     text,
  quantity        numeric(14,2) NOT NULL CHECK (quantity >= 0),
  reserved_qty    numeric(14,2) NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
  sold_qty        numeric(14,2) NOT NULL DEFAULT 0 CHECK (sold_qty >= 0),
  unit            text NOT NULL DEFAULT 'kg' CHECK (unit IN ('kg','tonne','bag','crate','bunch','head','litre')),
  price           numeric(14,2) NOT NULL CHECK (price >= 0),
  currency        char(3) NOT NULL DEFAULT 'BWP',
  status          listing_status NOT NULL DEFAULT 'DRAFT',
  image_url       text,
  location_text   text,
  published_at    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  deleted_at      timestamptz,
  CONSTRAINT listing_stock_sane CHECK (reserved_qty + sold_qty <= quantity)
);
CREATE INDEX listings_status_idx  ON produce_listings (status) WHERE deleted_at IS NULL;
CREATE INDEX listings_farmer_idx  ON produce_listings (farmer_id) WHERE deleted_at IS NULL;
CREATE INDEX listings_produce_idx ON produce_listings (lower(produce));
CREATE TRIGGER listings_set_updated_at BEFORE UPDATE ON produce_listings
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE orders (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id        uuid NOT NULL REFERENCES produce_listings(id) ON DELETE RESTRICT,
  buyer_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  farmer_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  quantity          numeric(14,2) NOT NULL CHECK (quantity > 0),
  unit_price        numeric(14,2) NOT NULL CHECK (unit_price >= 0),
  total_price       numeric(14,2) NOT NULL CHECK (total_price >= 0),
  currency          char(3) NOT NULL DEFAULT 'BWP',
  status            order_status NOT NULL DEFAULT 'PENDING',
  note              text,
  buyer_contact     text,                 -- shared with farmer only after order
  farmer_decision_at timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX orders_buyer_idx   ON orders (buyer_id, created_at DESC);
CREATE INDEX orders_farmer_idx  ON orders (farmer_id, created_at DESC);
CREATE INDEX orders_listing_idx ON orders (listing_id);
CREATE INDEX orders_status_idx  ON orders (status);
CREATE TRIGGER orders_set_updated_at BEFORE UPDATE ON orders
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMIT;

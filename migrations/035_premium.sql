-- Premium berlangganan (Sociabuzz). `premium` (boolean) tetap = pemberian manual owner/permanen;
-- `premium_until` = masa langganan berbayar. Premium aktif bila salah satunya berlaku.
ALTER TABLE users ADD COLUMN IF NOT EXISTS premium_until timestamptz;

-- Foto profil boleh GIF (khusus Premium): jenis berkasnya disimpan bersama gambar.
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_mime text NOT NULL DEFAULT 'image/jpeg';

-- Tampilan profil Premium: tema, frame avatar, background header (JPEG) + blur (0-25) & posisi (0-100).
ALTER TABLE users ADD COLUMN IF NOT EXISTS theme text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS frame text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS header_bg bytea;
ALTER TABLE users ADD COLUMN IF NOT EXISTS header_v integer NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS header_blur smallint NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS header_pos smallint NOT NULL DEFAULT 50;

-- Pesanan Premium. Harga selalu ditetapkan server; status berubah ke 'paid' hanya setelah Sociabuzz melaporkan sukses.
CREATE TABLE IF NOT EXISTS premium_orders (
  id          text PRIMARY KEY,
  user_id     integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plan        text NOT NULL,
  months      integer NOT NULL,
  method      text NOT NULL,
  amount      integer NOT NULL,
  total       integer NOT NULL,
  status      text NOT NULL DEFAULT 'pending',
  sb_order    text,
  sb_inv      text,
  pending_url text,
  pay_info    jsonb NOT NULL DEFAULT '{}',
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  paid_at     timestamptz,
  checked_at  timestamptz
);
CREATE INDEX IF NOT EXISTS premium_orders_user ON premium_orders (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS premium_orders_pending ON premium_orders (created_at) WHERE status = 'pending';
CREATE UNIQUE INDEX IF NOT EXISTS premium_orders_inv ON premium_orders (sb_inv) WHERE sb_inv IS NOT NULL;

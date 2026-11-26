-- Nama tampilan (bebas, opsional; kosong = pakai username) dan waktu terakhir username diganti (untuk cooldown 7 hari).
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS username_changed_at timestamptz;

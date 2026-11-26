-- Pengumuman bisa tampil sebagai popup di tengah layar (mis. info pembaruan aplikasi): gambar, versi, teks tombol.
ALTER TABLE announcements
  ADD COLUMN IF NOT EXISTS popup       boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS image_url   text,
  ADD COLUMN IF NOT EXISTS version     text,
  ADD COLUMN IF NOT EXISTS button_text text,
  ADD COLUMN IF NOT EXISTS cancel_text text;

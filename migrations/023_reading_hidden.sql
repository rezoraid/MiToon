-- Pemilik akun boleh menyembunyikan daftar "Sedang dibaca" dari profil publik. Progres tetap disimpan (tampil lagi bila dibuka).
ALTER TABLE users ADD COLUMN IF NOT EXISTS reading_hidden boolean NOT NULL DEFAULT false;

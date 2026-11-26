-- Pemilik centang verified boleh menyembunyikannya dari tampilan publik (profil & komentar). Status verified di server tetap.
ALTER TABLE users ADD COLUMN IF NOT EXISTS verified_hidden boolean NOT NULL DEFAULT false;

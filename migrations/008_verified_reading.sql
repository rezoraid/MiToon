-- Lencana verified (centang biru): ditetapkan server, bukan dari app.
ALTER TABLE users ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT false;
-- Akun owner yang sudah terdaftar langsung diverifikasi (akun baru lewat Google ditandai di AuthService.google).
UPDATE users SET verified = true WHERE lower(email) = 'hihanghoheng1507@gmail.com';

-- Komik yang sedang dibaca tiap pengguna (satu baris per seri; ditimpa chapter terakhirnya). Dipakai profil publik.
CREATE TABLE IF NOT EXISTS reading_progress (
  user_id        integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  series_id      text    NOT NULL,                 -- id unified "<provider>:<ref>"
  title          text    NOT NULL DEFAULT '',      -- cadangan bila seri belum ada di tabel series
  chapter_id     text    NOT NULL,
  chapter_number text    NOT NULL DEFAULT '',
  chapters_read  integer NOT NULL DEFAULT 0,
  read_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, series_id)
);
CREATE INDEX IF NOT EXISTS reading_progress_user_idx ON reading_progress (user_id, read_at DESC);

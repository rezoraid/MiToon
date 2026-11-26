-- Streak membaca: satu baris per (pengguna, hari WIB) bila pengguna membaca setidaknya satu chapter hari itu.
-- Terpisah dari xp_events karena XP hanya diberikan sekali per chapter & ada batas harian, sedangkan streak harus
-- tetap naik walau chapter yang dibaca sudah pernah dihitung atau kuota XP harian sudah habis.
CREATE TABLE IF NOT EXISTS read_days (
  user_id integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day     date    NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Jakarta')::date),
  PRIMARY KEY (user_id, day)
);
-- Isi awal dari riwayat XP baca supaya streak pengguna lama tidak mulai dari nol.
INSERT INTO read_days (user_id, day)
SELECT DISTINCT user_id, day FROM xp_events WHERE kind = 'read'
ON CONFLICT DO NOTHING;

-- Notifikasi: balasan & like pada komentar kita diturunkan langsung dari tabel comments / comment_votes.
-- Penanda "terakhir dilihat": semua yang lebih baru dari ini = belum dibaca (pil merah di navigasi).
ALTER TABLE users ADD COLUMN IF NOT EXISTS notif_seen_at timestamptz NOT NULL DEFAULT now();
-- Waktu like (baris lama = NULL, tidak dimunculkan sebagai notifikasi).
ALTER TABLE comment_votes ADD COLUMN IF NOT EXISTS created_at timestamptz;
ALTER TABLE comment_votes ALTER COLUMN created_at SET DEFAULT now();
CREATE INDEX IF NOT EXISTS comments_reply_to_idx ON comments (reply_to_user_id, id DESC) WHERE reply_to_user_id IS NOT NULL;

-- Riwayat komentar per pengguna (GET /comments/mine): urut id terbaru, dipaginasi keyset.
CREATE INDEX IF NOT EXISTS comments_user_idx ON comments (user_id, id DESC);

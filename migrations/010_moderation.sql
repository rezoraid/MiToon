-- Moderasi akun oleh owner: blokir (tidak bisa masuk/memakai akun) dan bisukan (tidak bisa berkomentar).
ALTER TABLE users ADD COLUMN IF NOT EXISTS banned_at timestamptz;
ALTER TABLE users ADD COLUMN IF NOT EXISTS ban_reason text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS muted_until timestamptz;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mute_reason text;
CREATE INDEX IF NOT EXISTS users_banned_idx ON users (id) WHERE banned_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_muted_idx ON users (id) WHERE muted_until IS NOT NULL;

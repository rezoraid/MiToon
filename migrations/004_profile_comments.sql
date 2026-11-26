-- Foto profil (JPEG kecil disimpan di DB; avatar_v dipakai untuk cache-busting) dan komentar.
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar bytea;
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_v integer NOT NULL DEFAULT 0;

-- target = "s:<id seri>" (halaman detail) atau "c:<id chapter>" (reader).
CREATE TABLE IF NOT EXISTS comments (
  id         bigserial PRIMARY KEY,
  target     text NOT NULL,
  user_id    integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body       text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS comments_target_idx ON comments (target, id DESC);

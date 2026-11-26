-- XP & level: total XP per user + buku besar (ledger) supaya tiap aksi hanya dihitung sekali dan ada batas harian.
ALTER TABLE users ADD COLUMN IF NOT EXISTS xp integer NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS xp_events (
  id         bigserial PRIMARY KEY,
  user_id    integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind       text    NOT NULL,
  ref        text    NOT NULL,
  xp         integer NOT NULL,
  day        date    NOT NULL DEFAULT ((now() AT TIME ZONE 'Asia/Jakarta')::date),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, kind, ref)
);
CREATE INDEX IF NOT EXISTS xp_events_user_day ON xp_events (user_id, kind, day);

-- Komentar: balasan (1 tingkat; parent_id selalu komentar teratas) dan like/dislike.
ALTER TABLE comments ADD COLUMN IF NOT EXISTS parent_id bigint REFERENCES comments(id) ON DELETE CASCADE;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS reply_to_user_id integer REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS comments_parent_idx ON comments (parent_id, id) WHERE parent_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS comment_votes (
  comment_id bigint   NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
  user_id    integer  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  value      smallint NOT NULL CHECK (value IN (-1, 1)),
  PRIMARY KEY (comment_id, user_id)
);

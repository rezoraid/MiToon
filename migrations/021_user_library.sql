CREATE TABLE IF NOT EXISTS user_library (
  user_id             integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  series_id           text    NOT NULL,
  title               text    NOT NULL DEFAULT '',
  cover               text    NOT NULL DEFAULT '',
  provider            text    NOT NULL DEFAULT '',
  bookmarked          boolean NOT NULL DEFAULT false,
  last_chapter_id     text,
  last_chapter_number text,
  read_at             bigint  NOT NULL DEFAULT 0,
  read                text[]  NOT NULL DEFAULT '{}',
  cleared             boolean NOT NULL DEFAULT false,
  updated_at          bigint  NOT NULL DEFAULT 0,
  synced_at           bigint  NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, series_id)
);
CREATE INDEX IF NOT EXISTS user_library_sync_idx ON user_library (user_id, synced_at);

-- Pengumuman untuk aplikasi (GET /v1/announcements). Dikelola lewat endpoint admin atau SQL langsung.
CREATE TABLE announcements (
  id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title      text NOT NULL,
  body       text NOT NULL DEFAULT '',
  url        text,
  active     boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz
);
CREATE INDEX announcements_active_idx ON announcements (created_at DESC) WHERE active;

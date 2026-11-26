-- Pengaturan push notification (diatur dari panel admin) + riwayat pengiriman manual / gagal.
CREATE TABLE push_settings (
  id          smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  enabled     boolean NOT NULL DEFAULT true,
  new_chapter boolean NOT NULL DEFAULT true,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO push_settings DEFAULT VALUES;

CREATE TABLE push_log (
  id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind       text NOT NULL,
  title      text NOT NULL,
  body       text NOT NULL DEFAULT '',
  series_id  text,
  ok         boolean NOT NULL,
  error      text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX push_log_created_idx ON push_log (created_at DESC);

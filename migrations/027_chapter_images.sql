CREATE TABLE IF NOT EXISTS chapter_images (
  provider    text NOT NULL,
  ref         text NOT NULL,
  number      text NOT NULL DEFAULT '',
  images      jsonb NOT NULL,
  headers     jsonb NOT NULL DEFAULT '{}'::jsonb,
  prev        text,
  next        text,
  series_id   text,
  source      text NOT NULL DEFAULT '',
  verified_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, ref)
);

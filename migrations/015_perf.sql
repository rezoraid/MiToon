-- Index pendukung query daftar (latest per provider, filter format, dedupe lintas provider) agar tetap cepat saat katalog membesar.
CREATE INDEX IF NOT EXISTS series_provider_seen_idx ON series (provider, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS series_type_idx ON series (type) WHERE type IS NOT NULL;
CREATE INDEX IF NOT EXISTS series_dedupe_idx ON series (norm_title, provider, last_seen_at DESC);

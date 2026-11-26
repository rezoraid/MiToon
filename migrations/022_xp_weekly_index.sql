-- Papan peringkat mingguan menjumlahkan xp_events per rentang hari; indeks ini menghindari scan seluruh buku besar.
CREATE INDEX IF NOT EXISTS xp_events_day_user ON xp_events (day, user_id) INCLUDE (xp);

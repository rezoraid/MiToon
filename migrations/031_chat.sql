-- Global Chat: satu ruang obrolan untuk semua pengguna.
-- Tabel ini sengaja kecil: setiap pesan baru langsung diikuti pemangkasan sehingga hanya 50 pesan terakhir yang tersimpan
-- (lihat ChatService.add). Pesan lama TIDAK diarsipkan.
CREATE TABLE IF NOT EXISTS chat_messages (
  id         bigserial PRIMARY KEY,
  user_id    integer     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body       text        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 500),
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Batas kirim per akun (hitung pesan 10 detik terakhir).
CREATE INDEX IF NOT EXISTS chat_messages_user_recent ON chat_messages (user_id, created_at DESC);

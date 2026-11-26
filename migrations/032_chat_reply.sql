-- Reply pesan Global Chat. Yang disimpan adalah cuplikan (nama + isi) pesan yang dibalas, bukan FK:
-- pesan lama dipangkas otomatis (hanya 50 terakhir), jadi kutipan harus tetap tampil walau aslinya sudah hilang.
ALTER TABLE chat_messages
  ADD COLUMN IF NOT EXISTS reply_to   bigint,
  ADD COLUMN IF NOT EXISTS reply_user text,
  ADD COLUMN IF NOT EXISTS reply_body text;

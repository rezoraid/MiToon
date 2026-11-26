-- Pengaman: pastikan kolom XP bigint (migrasi 033 bisa terlewat di deploy lama). Aman diulang.
ALTER TABLE users ALTER COLUMN xp TYPE bigint;

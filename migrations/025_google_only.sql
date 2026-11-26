-- Login hanya lewat Google: hapus akun tamu, kolom tamu, dan hash password.
DELETE FROM users WHERE is_guest;
DROP INDEX IF EXISTS users_guest_created;
ALTER TABLE users DROP COLUMN IF EXISTS is_guest;
ALTER TABLE users DROP COLUMN IF EXISTS password_hash;

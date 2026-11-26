-- Akun tamu: masuk tanpa daftar, fitur dibatasi. Dihapus otomatis bila tak pernah didaftarkan.
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_guest boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS users_guest_created ON users (created_at) WHERE is_guest;

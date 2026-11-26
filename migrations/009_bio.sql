-- Bio profil (opsional, maks. 150 karakter; divalidasi di AuthService.updateProfile).
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio text;

-- Akun pengguna: username+password, atau masuk via Google (google_sub).
CREATE TABLE IF NOT EXISTS users (
  id            serial PRIMARY KEY,
  username      text   NOT NULL,
  email         text,
  password_hash text,
  google_sub    text   UNIQUE,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;

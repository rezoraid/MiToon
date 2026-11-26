-- Fitur background profil dihapus total.
ALTER TABLE users DROP COLUMN IF EXISTS banner;
ALTER TABLE users DROP COLUMN IF EXISTS banner_v;

-- Premium Member: ditetapkan owner lewat panel admin (bukan dari app). Tampil di profil sendiri, profil publik, dan komentar.
ALTER TABLE users ADD COLUMN IF NOT EXISTS premium boolean NOT NULL DEFAULT false;

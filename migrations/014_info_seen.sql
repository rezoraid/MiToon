-- Tab Informasi (pengumuman admin) punya penanda "terakhir dilihat" sendiri, terpisah dari notif_seen_at (balasan & like),
-- supaya membuka salah satu tab tidak menghapus pil merah tab lainnya.
ALTER TABLE users ADD COLUMN IF NOT EXISTS info_seen_at timestamptz NOT NULL DEFAULT now();

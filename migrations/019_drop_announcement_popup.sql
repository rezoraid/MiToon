-- Fitur popup/info pembaruan dihapus: buang pengumuman popup yang masih tersimpan, lalu kolom khususnya.
DELETE FROM announcements WHERE popup;
ALTER TABLE announcements
  DROP COLUMN IF EXISTS popup,
  DROP COLUMN IF EXISTS image_url,
  DROP COLUMN IF EXISTS version,
  DROP COLUMN IF EXISTS button_text,
  DROP COLUMN IF EXISTS cancel_text;

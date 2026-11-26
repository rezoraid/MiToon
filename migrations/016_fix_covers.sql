-- Bersihkan cover rusak (placeholder data:/path relatif) agar terisi ulang oleh sinkronisasi berikutnya.
UPDATE series SET cover = '' WHERE cover <> '' AND (cover !~ '^https?://' OR cover ~ 'data:');

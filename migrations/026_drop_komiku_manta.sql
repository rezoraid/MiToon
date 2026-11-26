DELETE FROM series WHERE provider IN ('komiku', 'manta');
DELETE FROM providers WHERE key IN ('komiku', 'manta');
DELETE FROM genres g WHERE NOT EXISTS (SELECT 1 FROM series_genres sg WHERE sg.genre_id = g.id);

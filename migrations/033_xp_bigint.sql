-- XP total memakai bigint: batas integer (2,1 miliar) dan batas panel admin lama (100 juta XP = Lv. 663) terlalu kecil untuk level sampai 10.000 (~334 miliar XP).
ALTER TABLE users ALTER COLUMN xp TYPE bigint;

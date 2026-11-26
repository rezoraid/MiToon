-- Gabungkan varian "[Full ver.]" dengan seri aslinya (norm_title sama) supaya tidak tampil dobel.
UPDATE series
SET norm_title = regexp_replace(norm_title, 'fullver(sion)?$', '')
WHERE title ~* '[\[(（【]\s*full\s*ver(sion)?\.?\s*[\])）】]'
  AND norm_title ~ 'fullver(sion)?$'
  AND regexp_replace(norm_title, 'fullver(sion)?$', '') <> '';

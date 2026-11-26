import { levelFor, xpForLevel, XP_MAX } from '../core/xp.js'
import type { Pool, PoolClient } from 'pg'
import type { ChapterContent, ChapterSummary, DbPage, GenreFilters, GenreRow, SortKey, ProviderKey, SeriesDetail, SeriesSummary, SeriesStatus, SourceRef, Store } from '../core/types.js'
import { normTitle, slugify } from '../core/util.js'

type Row = Record<string, any>

const UPSERT_SUMMARIES = `
INSERT INTO series (provider, ref, title, norm_title, cover, url, type, status, rating, chapter_count, latest_chapter)
SELECT * FROM unnest($1::text[], $2::text[], $3::text[], $4::text[], $5::text[], $6::text[], $7::text[], $8::text[],
                     $9::float8[], $10::int[], $11::text[])
ORDER BY 1, 2
ON CONFLICT (provider, ref) DO UPDATE SET
  title          = EXCLUDED.title,
  norm_title     = EXCLUDED.norm_title,
  cover          = CASE WHEN EXCLUDED.cover ~ '^https?://' AND EXCLUDED.cover !~ 'data:' THEN EXCLUDED.cover ELSE series.cover END,
  url            = EXCLUDED.url,
  type           = COALESCE(EXCLUDED.type, series.type),
  status         = COALESCE(EXCLUDED.status, series.status),
  rating         = COALESCE(EXCLUDED.rating, series.rating),
  chapter_count  = COALESCE(EXCLUDED.chapter_count, series.chapter_count),
  latest_chapter = COALESCE(EXCLUDED.latest_chapter, series.latest_chapter),
  last_seen_at   = now(),
  updated_at     = now()
RETURNING id, provider, ref`

const UPSERT_DETAIL = `
INSERT INTO series (provider, ref, title, norm_title, cover, url, type, status, synopsis, author, artist, year,
                    rating, chapter_count, latest_chapter, detail_fetched_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, now())
ON CONFLICT (provider, ref) DO UPDATE SET
  title             = EXCLUDED.title,
  norm_title        = EXCLUDED.norm_title,
  cover             = CASE WHEN EXCLUDED.cover ~ '^https?://' AND EXCLUDED.cover !~ 'data:' THEN EXCLUDED.cover ELSE series.cover END,
  url               = EXCLUDED.url,
  type              = COALESCE(EXCLUDED.type, series.type),
  status            = COALESCE(EXCLUDED.status, series.status),
  synopsis          = COALESCE(NULLIF(EXCLUDED.synopsis, ''), series.synopsis),
  author            = COALESCE(NULLIF(EXCLUDED.author, ''), series.author),
  artist            = COALESCE(NULLIF(EXCLUDED.artist, ''), series.artist),
  year              = COALESCE(EXCLUDED.year, series.year),
  rating            = COALESCE(EXCLUDED.rating, series.rating),
  chapter_count     = COALESCE(EXCLUDED.chapter_count, series.chapter_count),
  latest_chapter    = COALESCE(EXCLUDED.latest_chapter, series.latest_chapter),
  detail_fetched_at = now(),
  last_seen_at      = now(),
  updated_at        = now()
RETURNING id`

const UPSERT_GENRES = `
INSERT INTO genres (slug, name)
SELECT DISTINCT ON (slug) slug, name FROM unnest($1::text[], $2::text[]) AS t(slug, name)
ORDER BY slug
ON CONFLICT (slug) DO NOTHING`

const UPSERT_CHAPTERS = `
INSERT INTO chapters (series_id, ref, number, number_text, title, released_at)
SELECT $1, t.ref, t.number, t.number_text, t.title, t.released_at
FROM unnest($2::text[], $3::float8[], $4::text[], $5::text[], $6::timestamptz[]) AS t(ref, number, number_text, title, released_at)
ORDER BY t.ref
ON CONFLICT (series_id, ref) DO UPDATE SET
  number      = EXCLUDED.number,
  number_text = EXCLUDED.number_text,
  title       = EXCLUDED.title,
  released_at = COALESCE(EXCLUDED.released_at, chapters.released_at)
WHERE (chapters.number_text, chapters.title, chapters.released_at) IS DISTINCT FROM (EXCLUDED.number_text, EXCLUDED.title, COALESCE(EXCLUDED.released_at, chapters.released_at))`

const GENRE_ORDER: Record<SortKey, string> = {
  latest: 'x.last_seen_at DESC, x.id',
  popular: 'x.score DESC, x.rating DESC NULLS LAST, x.last_seen_at DESC, x.id',
  rating: 'x.rating DESC NULLS LAST, x.last_seen_at DESC, x.id',
  az: 'lower(x.title), x.id'
}

const genrePairs = (names: string[]) => {
  const map = new Map<string, string>()
  for (const name of names) {
    const slug = slugify(name)
    if (slug && !map.has(slug)) map.set(slug, name)
  }
  return { slugs: [...map.keys()], names: [...map.values()] }
}

const dedupe = <T>(items: T[], key: (t: T) => string) => [...new Map(items.map(i => [key(i), i])).values()]

export class PgStore implements Store {
  private newChaptersCb?: (series: { id: string; title: string }, chapters: ChapterSummary[]) => Promise<void> | void

  constructor(private readonly pool: Pool) {}

  onNewChapters(fn: (series: { id: string; title: string }, chapters: ChapterSummary[]) => Promise<void> | void) {
    this.newChaptersCb = fn
  }

  private async tx<T>(fn: (c: PoolClient) => Promise<T>): Promise<T> {
    const c = await this.pool.connect()
    try {
      await c.query('BEGIN')
      const out = await fn(c)
      await c.query('COMMIT')
      return out
    } catch (e) {
      await c.query('ROLLBACK').catch(() => {})
      throw e
    } finally {
      c.release()
    }
  }

  async ping() {
    try {
      await this.pool.query('SELECT 1')
      return true
    } catch {
      return false
    }
  }

  async syncProviders(list: { key: string; name: string; baseUrl: string }[]) {
    await this.pool.query(
      `INSERT INTO providers (key, name, base_url, priority)
       SELECT t.key, t.name, t.base_url, (t.ord - 1)::smallint
       FROM unnest($1::text[], $2::text[], $3::text[]) WITH ORDINALITY AS t(key, name, base_url, ord)
       ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name, base_url = EXCLUDED.base_url, priority = EXCLUDED.priority`,
      [list.map(p => p.key), list.map(p => p.name), list.map(p => p.baseUrl)]
    )
  }

  async saveSummaries(items: SeriesSummary[]) {
    const rows = dedupe(items, i => i.id)
    if (!rows.length) return
    const ref = (s: SeriesSummary) => s.id.slice(s.provider.length + 1)
    await this.tx(async c => {
      const { rows: saved } = await c.query<Row>(UPSERT_SUMMARIES, [
        rows.map(s => s.provider),
        rows.map(ref),
        rows.map(s => s.title),
        rows.map(s => normTitle(s.title)),
        rows.map(s => s.cover),
        rows.map(s => s.url),
        rows.map(s => s.type),
        rows.map(s => s.status),
        rows.map(s => s.rating),
        rows.map(s => s.chapterCount),
        rows.map(s => s.latestChapter)
      ])
      const dbId = new Map(saved.map(r => [`${r.provider}:${r.ref}`, r.id as string]))
      const links: { sid: string; slug: string }[] = []
      const all = new Map<string, string>()
      for (const s of rows) {
        const { slugs, names } = genrePairs(s.genres)
        slugs.forEach((slug, i) => {
          all.set(slug, names[i])
          const id = dbId.get(s.id)
          if (id) links.push({ sid: id, slug })
        })
      }
      if (!links.length) return
      await c.query(UPSERT_GENRES, [[...all.keys()], [...all.values()]])
      await c.query(
        `INSERT INTO series_genres (series_id, genre_id)
         SELECT t.sid, g.id FROM unnest($1::bigint[], $2::text[]) AS t(sid, slug) JOIN genres g ON g.slug = t.slug
         ON CONFLICT DO NOTHING`,
        [links.map(l => l.sid), links.map(l => l.slug)]
      )
    })
  }

  async saveDetail(d: SeriesDetail) {
    const ref = d.id.slice(d.provider.length + 1)
    let added: ChapterSummary[] = []
    await this.tx(async c => {
      const { rows } = await c.query<Row>(UPSERT_DETAIL, [
        d.provider, ref, d.title, normTitle(d.title), d.cover, d.url, d.type, d.status,
        d.synopsis, d.author, d.artist, d.year, d.rating, d.chapters.length || d.chapterCount, d.latestChapter
      ])
      const id = rows[0].id as string

      const { slugs, names } = genrePairs(d.genres)
      if (slugs.length) {
        await c.query(UPSERT_GENRES, [slugs, names])
        await c.query(
          `DELETE FROM series_genres sg USING genres g
           WHERE sg.series_id = $1 AND g.id = sg.genre_id AND g.slug <> ALL($2::text[])`,
          [id, slugs]
        )
        await c.query(
          `INSERT INTO series_genres (series_id, genre_id) SELECT $1, g.id FROM genres g WHERE g.slug = ANY($2::text[])
           ON CONFLICT DO NOTHING`,
          [id, slugs]
        )
      }

      const chapters = dedupe(d.chapters, ch => ch.id)
      if (chapters.length && this.newChaptersCb) {
        const known = new Set((await c.query<Row>('SELECT ref FROM chapters WHERE series_id = $1', [id])).rows.map(r => r.ref as string))
        if (known.size > 0) added = chapters.filter(ch => !known.has(ch.id.slice(d.provider.length + 1)))
      }
      if (chapters.length) {
        const n = (s: string) => (Number.isFinite(parseFloat(s)) ? parseFloat(s) : null)
        await c.query(UPSERT_CHAPTERS, [
          id,
          chapters.map(ch => ch.id.slice(d.provider.length + 1)),
          chapters.map(ch => n(ch.number)),
          chapters.map(ch => ch.number),
          chapters.map(ch => ch.title),
          chapters.map(ch => ch.date)
        ])
      }
    })
    if (added.length > 0 && added.length <= 5 && this.newChaptersCb) {
      await Promise.race([Promise.resolve(this.newChaptersCb({ id: d.id, title: d.title }, added)).catch(() => undefined), new Promise(r => setTimeout(r, 3000))])
    }
  }

  async detailLocal(provider: ProviderKey, ref: string): Promise<SeriesDetail | null> {
    const { rows } = await this.pool.query<Row>(
      'SELECT * FROM series WHERE provider = $1 AND ref = $2 AND detail_fetched_at IS NOT NULL',
      [provider, ref]
    )
    const s = rows[0]
    if (!s) return null
    const [[base], chapters] = await Promise.all([
      this.hydrate([s]),
      this.pool.query<Row>(
        'SELECT ref, number_text, title, released_at FROM chapters WHERE series_id = $1 ORDER BY number DESC NULLS LAST, id DESC',
        [s.id]
      )
    ])
    if (!base || !chapters.rows.length) return null
    return {
      ...base,
      synopsis: s.synopsis ?? '',
      author: s.author ?? '',
      artist: s.artist ?? '',
      year: s.year ?? null,
      chapters: chapters.rows.map((c: Row) => ({
        id: `${provider}:${c.ref}`,
        number: c.number_text as string,
        title: (c.title ?? '') as string,
        date: c.released_at ? new Date(c.released_at).toISOString() : null
      }))
    }
  }

  async findSeries(provider: ProviderKey, ref: string) {
    const { rows } = await this.pool.query<Row>('SELECT title, norm_title FROM series WHERE provider = $1 AND ref = $2', [provider, ref])
    return rows[0] ? { title: rows[0].title as string, normTitle: rows[0].norm_title as string } : null
  }

  async siblings(normTitleValue: string, exclude: ProviderKey) {
    const { rows } = await this.pool.query<Row>(
      `SELECT s.provider, s.ref FROM series s JOIN providers p ON p.key = s.provider
       WHERE s.norm_title = $1 AND s.provider <> $2 ORDER BY p.priority`,
      [normTitleValue, exclude]
    )
    return rows.map(r => ({ provider: r.provider as ProviderKey, ref: r.ref as string }))
  }

  async findChapter(provider: ProviderKey, ref: string) {
    const { rows } = await this.pool.query<Row>(
      `SELECT s.ref AS series_ref, c.number_text FROM chapters c JOIN series s ON s.id = c.series_id
       WHERE s.provider = $1 AND c.ref = $2 LIMIT 1`,
      [provider, ref]
    )
    return rows[0] ? { seriesRef: rows[0].series_ref as string, number: rows[0].number_text as string } : null
  }

  async chapterLocal(provider: ProviderKey, ref: string): Promise<ChapterContent | null> {
    const { rows } = await this.pool.query<Row>('SELECT number, images, headers, prev, next, series_id FROM chapter_images WHERE provider = $1 AND ref = $2', [provider, ref])
    const r = rows[0]
    if (!r || !Array.isArray(r.images) || !r.images.length) return null
    return { id: `${provider}:${ref}`, seriesId: (r.series_id ?? null) as string | null, number: String(r.number ?? ''), images: r.images as string[], prev: (r.prev ?? null) as string | null, next: (r.next ?? null) as string | null, imageHeaders: (r.headers ?? {}) as Record<string, string> }
  }

  async saveChapterImages(provider: ProviderKey, ref: string, c: ChapterContent, source: ProviderKey) {
    await this.pool.query(
      `INSERT INTO chapter_images (provider, ref, number, images, headers, prev, next, series_id, source, verified_at)
       VALUES ($1, $2, $3, $4::jsonb, $5::jsonb, $6, $7, $8, $9, now())
       ON CONFLICT (provider, ref) DO UPDATE SET number = EXCLUDED.number, images = EXCLUDED.images, headers = EXCLUDED.headers,
         prev = EXCLUDED.prev, next = EXCLUDED.next, series_id = EXCLUDED.series_id, source = EXCLUDED.source, verified_at = now()`,
      [provider, ref, c.number ?? '', JSON.stringify(c.images), JSON.stringify(c.imageHeaders ?? {}), c.prev, c.next, c.seriesId, source]
    )
  }

  async adminChapterTarget(chapterId: number) {
    const { rows } = await this.pool.query<Row>(
      'SELECT s.provider, s.ref AS series_ref, c.ref, c.number_text FROM chapters c JOIN series s ON s.id = c.series_id WHERE c.id = $1',
      [chapterId]
    )
    const r = rows[0]
    return r ? { provider: r.provider as ProviderKey, seriesRef: r.series_ref as string, ref: r.ref as string, number: r.number_text as string } : null
  }

  // ---- listing dari DB (dedupe lintas provider berdasarkan norm_title) ----

  /**
   * Satu baris per norm_title (provider berprioritas tertinggi menang).
   * Genre & sumber alternatif diambil terpisah hanya untuk baris yang lolos LIMIT.
   */
  private async list(
    q: { select?: string; join?: string; where?: string; params: unknown[]; order: string },
    { limit, offset }: DbPage
  ): Promise<SeriesSummary[]> {
    const n = q.params.length
    const { rows } = await this.pool.query<Row>(
      `SELECT x.* FROM (
         SELECT DISTINCT ON (s.norm_title) s.*${q.select ?? ''}
         FROM series s JOIN providers pr ON pr.key = s.provider ${q.join ?? ''}
         ${q.where ?? ''}
         ORDER BY s.norm_title, pr.priority, (s.title ~* '[\\[(（【]\\s*full\\s*ver') ASC, s.last_seen_at DESC
       ) x ORDER BY ${q.order} LIMIT $${n + 1} OFFSET $${n + 2}`,
      [...q.params, limit, offset]
    )
    return this.hydrate(rows)
  }

  private async hydrate(rows: Row[]): Promise<SeriesSummary[]> {
    if (!rows.length) return []
    const [genres, sources] = await Promise.all([
      this.pool.query<Row>(
        `SELECT sg.series_id, array_agg(g.name ORDER BY g.name) AS names
         FROM series_genres sg JOIN genres g ON g.id = sg.genre_id WHERE sg.series_id = ANY($1::bigint[]) GROUP BY sg.series_id`,
        [rows.map(r => r.id)]
      ),
      this.pool.query<Row>(
        `SELECT s.norm_title, s.provider, s.ref FROM series s JOIN providers p ON p.key = s.provider
         WHERE s.norm_title = ANY($1::text[]) ORDER BY p.priority`,
        [rows.map(r => r.norm_title)]
      )
    ])
    const genreMap = new Map(genres.rows.map(r => [String(r.series_id), r.names as string[]]))
    const sourceMap = new Map<string, SourceRef[]>()
    for (const s of sources.rows) {
      const list = sourceMap.get(s.norm_title) ?? []
      list.push({ provider: s.provider, id: `${s.provider}:${s.ref}` })
      sourceMap.set(s.norm_title, list)
    }
    return rows.map(r => {
      const id = `${r.provider}:${r.ref}`
      const others = (sourceMap.get(r.norm_title) ?? []).filter(s => s.id !== id)
      return {
        id,
        provider: r.provider as ProviderKey,
        title: r.title,
        cover: r.cover,
        url: r.url,
        type: r.type,
        status: r.status as SeriesStatus | null,
        genres: genreMap.get(String(r.id)) ?? [],
        rating: r.rating,
        chapterCount: r.chapter_count,
        latestChapter: r.latest_chapter,
        sources: [{ provider: r.provider, id }, ...others]
      }
    })
  }

  async searchLocal(q: string, p: DbPage) {
    const nq = normTitle(q)
    if (!nq) return []
    const filter = p.provider ? ' AND s.provider = $2' : ''
    return this.list(
      {
        where: `WHERE s.norm_title LIKE '%' || $1 || '%'${filter}`,
        params: p.provider ? [nq, p.provider] : [nq],
        order: 'similarity(x.norm_title, $1) DESC, x.last_seen_at DESC, x.id'
      },
      p
    )
  }

  latestLocal(p: DbPage) {
    return this.list(
      { where: p.provider ? 'WHERE s.provider = $1' : '', params: p.provider ? [p.provider] : [], order: 'x.last_seen_at DESC, x.id' },
      p
    )
  }

  /** Skor = total hit detail dalam `days` hari terakhir (semua provider untuk judul yang sama); seri tanpa hit diurutkan rating lalu terbaru. */
  popular(p: DbPage & { days: number }) {
    return this.list(
      {
        select: ', SUM(COALESCE(v.views, 0)) OVER (PARTITION BY s.norm_title) AS score',
        join: `LEFT JOIN (SELECT series_id, SUM(views) AS views FROM series_views
                          WHERE day > CURRENT_DATE - $1::int GROUP BY series_id) v ON v.series_id = s.id`,
        where: 'WHERE ($2::text IS NULL OR s.provider = $2::text)',
        params: [p.days, p.provider ?? null],
        order: 'x.score DESC, x.rating DESC NULLS LAST, x.last_seen_at DESC, x.id'
      },
      p
    )
  }

  /**
   * Rekomendasi = rating tertinggi dulu, lalu seri dengan chapter terbanyak, lalu yang terbaru.
   * Rating BUKAN syarat: Shinigami tidak punya rating di list-nya (null), jadi dulu hasilnya kosong.
   * Seri tanpa cover diurutkan paling belakang (bukan dibuang, supaya hasil tidak kosong).
   */
  recommended(p: DbPage) {
    return this.list(
      {
        where: p.provider ? 'WHERE s.provider = $1' : '',
        params: p.provider ? [p.provider] : [],
        order: "(x.cover = '') ASC, x.rating DESC NULLS LAST, x.chapter_count DESC NULLS LAST, x.last_seen_at DESC, x.id"
      },
      p
    )
  }

  async genres(): Promise<GenreRow[]> {
    const { rows } = await this.pool.query<Row>(
      `SELECT g.slug, g.name, COUNT(DISTINCT s.norm_title)::int AS n
       FROM genres g JOIN series_genres sg ON sg.genre_id = g.id JOIN series s ON s.id = sg.series_id
       GROUP BY g.id ORDER BY n DESC, g.name`
    )
    return rows.map(r => ({ slug: r.slug, name: r.name, count: r.n }))
  }

  /** Sortir & filter format/status dilakukan di SQL (whitelist, bukan input mentah). */
  byGenre(slug: string, p: DbPage & GenreFilters) {
    const params: unknown[] = [slug]
    const where = ['g.slug = $1']
    const add = (cond: string, v: unknown) => {
      params.push(v)
      where.push(cond.replace('?', `$${params.length}`))
    }
    if (p.provider) add('s.provider = ?', p.provider)
    if (p.type) add('s.type = ?', p.type)
    if (p.status) add('s.status = ?', p.status)
    const sort: SortKey = p.sort ?? 'latest'
    const popular = sort === 'popular'
    return this.list(
      {
        select: popular ? ', SUM(COALESCE(v.views, 0)) OVER (PARTITION BY s.norm_title) AS score' : '',
        join:
          'JOIN series_genres sg ON sg.series_id = s.id JOIN genres g ON g.id = sg.genre_id' +
          (popular
            ? ' LEFT JOIN (SELECT series_id, SUM(views) AS views FROM series_views WHERE day > CURRENT_DATE - 30 GROUP BY series_id) v ON v.series_id = s.id'
            : ''),
        where: `WHERE ${where.join(' AND ')}`,
        params,
        order: GENRE_ORDER[sort]
      },
      p
    )
  }

  async addViews(rows: { provider: ProviderKey; ref: string; n: number }[]) {
    if (!rows.length) return
    await this.pool.query(
      `INSERT INTO series_views (series_id, day, views)
       SELECT s.id, CURRENT_DATE, v.n
       FROM unnest($1::text[], $2::text[], $3::int[]) AS v(provider, ref, n)
       JOIN series s ON s.provider = v.provider AND s.ref = v.ref
       ORDER BY s.id
       ON CONFLICT (series_id, day) DO UPDATE SET views = series_views.views + EXCLUDED.views`,
      [rows.map(r => r.provider), rows.map(r => r.ref), rows.map(r => r.n)]
    )
  }

  /**
   * Seri yang perlu diperkaya lewat halaman detail: (1) cover kosong/rusak (diurutkan paling dulu; dicoba ulang tiap 6 jam
   * bila detail pun tak punya cover) atau (2) belum pernah diambil detailnya dan tanpa genre.
   */
  async needingDetail(limit: number) {
    const { rows } = await this.pool.query<Row>(
      `SELECT s.provider, s.ref FROM series s
       WHERE (
         (s.cover !~ '^https?://' OR s.cover ~ 'data:')
         AND (s.detail_fetched_at IS NULL OR s.detail_fetched_at < now() - interval '6 hours')
       ) OR (
         s.detail_fetched_at IS NULL AND NOT EXISTS (SELECT 1 FROM series_genres sg WHERE sg.series_id = s.id)
       )
       ORDER BY (s.cover !~ '^https?://' OR s.cover ~ 'data:') DESC, s.last_seen_at DESC LIMIT $1`,
      [limit]
    )
    return rows.map(r => ({ provider: r.provider as ProviderKey, ref: r.ref as string }))
  }

  async prune() {
    await this.pool.query(`DELETE FROM series_views WHERE day < CURRENT_DATE - 90`)
  }

  // ---- Admin: kontrol penuh atas data di DB ----

  async pushSettings() {
    const { rows } = await this.pool.query<Row>('SELECT enabled, new_chapter FROM push_settings WHERE id = 1')
    return { enabled: rows[0]?.enabled ?? true, newChapter: rows[0]?.new_chapter ?? true }
  }

  async savePushSettings(p: { enabled?: boolean; newChapter?: boolean }) {
    await this.pool.query(
      `INSERT INTO push_settings (id, enabled, new_chapter) VALUES (1, COALESCE($1, true), COALESCE($2, true))
       ON CONFLICT (id) DO UPDATE SET enabled = COALESCE($1, push_settings.enabled), new_chapter = COALESCE($2, push_settings.new_chapter), updated_at = now()`,
      [p.enabled ?? null, p.newChapter ?? null]
    )
    return this.pushSettings()
  }

  async logPush(e: { kind: string; title: string; body: string; seriesId: string | null; ok: boolean; error: string | null }) {
    await this.pool.query('INSERT INTO push_log (kind, title, body, series_id, ok, error) VALUES ($1, $2, $3, $4, $5, $6)', [e.kind, e.title, e.body, e.seriesId, e.ok, e.error])
    await this.pool.query('DELETE FROM push_log WHERE id NOT IN (SELECT id FROM push_log ORDER BY id DESC LIMIT 100)')
  }

  async pushLog(limit = 30) {
    const { rows } = await this.pool.query<Row>('SELECT kind, title, body, series_id, ok, error, created_at FROM push_log ORDER BY id DESC LIMIT $1', [limit])
    return rows.map(r => ({ kind: r.kind as string, title: r.title as string, body: r.body as string, seriesId: (r.series_id as string | null) ?? null, ok: r.ok as boolean, error: (r.error as string | null) ?? null, createdAt: new Date(r.created_at).getTime() }))
  }

  async adminStats() {
    const { rows } = await this.pool.query<Row>(`
      SELECT
        (SELECT COUNT(*)::int FROM series) AS series,
        (SELECT COUNT(*)::int FROM chapters) AS chapters,
        (SELECT COUNT(*)::int FROM users) AS users,
        (SELECT COUNT(*)::int FROM comments) AS comments,
        (SELECT COUNT(*)::int FROM genres) AS genres,
        (SELECT COUNT(*)::int FROM users WHERE banned_at IS NOT NULL) AS banned,
        (SELECT COUNT(*)::int FROM users WHERE muted_until > now()) AS muted,
        (SELECT COUNT(*)::int FROM series_views WHERE day >= CURRENT_DATE - 7) AS views_7d
    `)
    return rows[0] as {
      series: number; chapters: number; users: number; comments: number; genres: number; banned: number; muted: number; views_7d: number
    }
  }

  /** List seri mentah dari DB (tidak dedupe), untuk admin. */
  async adminListSeries(opts: { q?: string; provider?: string; limit: number; offset: number }) {
    const params: unknown[] = []
    const where: string[] = []
    if (opts.q) {
      params.push(`%${opts.q.toLowerCase()}%`)
      where.push(`(lower(s.title) LIKE $${params.length} OR lower(s.norm_title) LIKE $${params.length} OR s.ref LIKE $${params.length})`)
    }
    if (opts.provider) {
      params.push(opts.provider)
      where.push(`s.provider = $${params.length}`)
    }
    const w = where.length ? `WHERE ${where.join(' AND ')}` : ''
    params.push(opts.limit, opts.offset)
    const { rows } = await this.pool.query<Row>(
      `SELECT s.id, s.provider, s.ref, s.title, s.cover, s.url, s.type, s.status, s.synopsis, s.author, s.artist,
              s.year, s.rating, s.chapter_count, s.latest_chapter, s.detail_fetched_at, s.first_seen_at, s.last_seen_at, s.updated_at,
              (SELECT COUNT(*)::int FROM chapters c WHERE c.series_id = s.id) AS chapter_n,
              COALESCE((SELECT array_agg(g.name ORDER BY g.name) FROM series_genres sg JOIN genres g ON g.id = sg.genre_id WHERE sg.series_id = s.id), '{}') AS genres
       FROM series s ${w}
       ORDER BY s.updated_at DESC NULLS LAST, s.id DESC
       LIMIT $${params.length - 1} OFFSET $${params.length}`,
      params
    )
    const countParams = params.slice(0, -2)
    const { rows: cnt } = await this.pool.query<Row>(
      `SELECT COUNT(*)::int AS n FROM series s ${w}`,
      countParams
    )
    return {
      total: cnt[0]?.n ?? 0,
      items: rows.map(r => ({
        id: Number(r.id),
        provider: r.provider as string,
        ref: r.ref as string,
        unifiedId: `${r.provider}:${r.ref}`,
        title: r.title as string,
        cover: (r.cover ?? '') as string,
        url: (r.url ?? '') as string,
        type: r.type as string | null,
        status: r.status as string | null,
        synopsis: (r.synopsis ?? '') as string,
        author: (r.author ?? '') as string,
        artist: (r.artist ?? '') as string,
        year: r.year as number | null,
        rating: r.rating as number | null,
        chapterCount: r.chapter_count as number | null,
        latestChapter: r.latest_chapter as string | null,
        chapterN: Number(r.chapter_n ?? 0),
        genres: (r.genres as string[]) ?? [],
        detailFetchedAt: r.detail_fetched_at ? new Date(r.detail_fetched_at).toISOString() : null,
        firstSeenAt: r.first_seen_at ? new Date(r.first_seen_at).toISOString() : null,
        lastSeenAt: r.last_seen_at ? new Date(r.last_seen_at).toISOString() : null,
        updatedAt: r.updated_at ? new Date(r.updated_at).toISOString() : null
      }))
    }
  }

  async adminGetSeries(id: number) {
    const { rows } = await this.pool.query<Row>(
      `SELECT s.*, (SELECT COUNT(*)::int FROM chapters c WHERE c.series_id = s.id) AS chapter_n,
              COALESCE((SELECT array_agg(g.slug ORDER BY g.name) FROM series_genres sg JOIN genres g ON g.id = sg.genre_id WHERE sg.series_id = s.id), '{}') AS genre_slugs,
              COALESCE((SELECT array_agg(g.name ORDER BY g.name) FROM series_genres sg JOIN genres g ON g.id = sg.genre_id WHERE sg.series_id = s.id), '{}') AS genres
       FROM series s WHERE s.id = $1`,
      [id]
    )
    const r = rows[0]
    if (!r) return null
    const ch = await this.pool.query<Row>(
      `SELECT c.id, c.ref, c.number, c.number_text, c.title, c.released_at,
              EXISTS (SELECT 1 FROM chapter_images ci WHERE ci.provider = $2 AND ci.ref = c.ref) AS fixed
         FROM chapters c WHERE c.series_id = $1 ORDER BY c.number DESC NULLS LAST, c.id DESC`,
      [id, r.provider]
    )
    return {
      id: Number(r.id),
      provider: r.provider as string,
      ref: r.ref as string,
      unifiedId: `${r.provider}:${r.ref}`,
      title: r.title as string,
      cover: (r.cover ?? '') as string,
      url: (r.url ?? '') as string,
      type: r.type as string | null,
      status: r.status as string | null,
      synopsis: (r.synopsis ?? '') as string,
      author: (r.author ?? '') as string,
      artist: (r.artist ?? '') as string,
      year: r.year as number | null,
      rating: r.rating as number | null,
      chapterCount: r.chapter_count as number | null,
      latestChapter: r.latest_chapter as string | null,
      chapterN: Number(r.chapter_n ?? 0),
      genres: (r.genres as string[]) ?? [],
      genreSlugs: (r.genre_slugs as string[]) ?? [],
      detailFetchedAt: r.detail_fetched_at ? new Date(r.detail_fetched_at).toISOString() : null,
      firstSeenAt: r.first_seen_at ? new Date(r.first_seen_at).toISOString() : null,
      lastSeenAt: r.last_seen_at ? new Date(r.last_seen_at).toISOString() : null,
      updatedAt: r.updated_at ? new Date(r.updated_at).toISOString() : null,
      chapters: ch.rows.map(c => ({
        id: Number(c.id),
        ref: c.ref as string,
        number: c.number as number | null,
        numberText: c.number_text as string,
        title: (c.title ?? '') as string,
        releasedAt: c.released_at ? new Date(c.released_at).toISOString() : null,
        fixed: c.fixed === true
      }))
    }
  }

  async adminUpdateSeries(
    id: number,
    patch: {
      title?: string
      cover?: string
      url?: string
      type?: string | null
      status?: string | null
      synopsis?: string
      author?: string
      artist?: string
      year?: number | null
      rating?: number | null
      chapterCount?: number | null
      latestChapter?: string | null
      genres?: string[]
    }
  ) {
    const sets: string[] = []
    const params: unknown[] = []
    const add = (col: string, v: unknown) => {
      params.push(v)
      sets.push(`${col} = $${params.length}`)
    }
    if (patch.title !== undefined) {
      add('title', patch.title)
      params.push(normTitle(patch.title))
      sets.push(`norm_title = $${params.length}`)
    }
    if (patch.cover !== undefined) add('cover', patch.cover)
    if (patch.url !== undefined) add('url', patch.url)
    if (patch.type !== undefined) add('type', patch.type)
    if (patch.status !== undefined) {
      if (patch.status !== null && !['ongoing', 'completed', 'hiatus'].includes(patch.status)) {
        throw new Error('status harus ongoing|completed|hiatus atau null')
      }
      add('status', patch.status)
    }
    if (patch.synopsis !== undefined) add('synopsis', patch.synopsis)
    if (patch.author !== undefined) add('author', patch.author)
    if (patch.artist !== undefined) add('artist', patch.artist)
    if (patch.year !== undefined) add('year', patch.year)
    if (patch.rating !== undefined) add('rating', patch.rating)
    if (patch.chapterCount !== undefined) add('chapter_count', patch.chapterCount)
    if (patch.latestChapter !== undefined) add('latest_chapter', patch.latestChapter)
    if (!sets.length && patch.genres === undefined) return this.adminGetSeries(id)

    if (sets.length) {
      sets.push('updated_at = now()')
      params.push(id)
      const { rowCount } = await this.pool.query(
        `UPDATE series SET ${sets.join(', ')} WHERE id = $${params.length}`,
        params
      )
      if (!rowCount) return null
    }

    if (patch.genres !== undefined) {
      const slugs = patch.genres.map(g => slugify(g)).filter(Boolean)
      const names = patch.genres
      if (slugs.length) {
        await this.pool.query(
          `INSERT INTO genres (slug, name) SELECT * FROM unnest($1::text[], $2::text[]) ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name`,
          [slugs, names]
        )
      }
      await this.pool.query(`DELETE FROM series_genres WHERE series_id = $1`, [id])
      if (slugs.length) {
        await this.pool.query(
          `INSERT INTO series_genres (series_id, genre_id) SELECT $1, g.id FROM genres g WHERE g.slug = ANY($2::text[]) ON CONFLICT DO NOTHING`,
          [id, slugs]
        )
      }
    }
    return this.adminGetSeries(id)
  }

  async adminDeleteSeries(id: number) {
    const { rowCount } = await this.pool.query('DELETE FROM series WHERE id = $1', [id])
    return (rowCount ?? 0) > 0
  }

  async adminUpdateChapter(chapterId: number, patch: { title?: string; numberText?: string; number?: number | null }) {
    const sets: string[] = []
    const params: unknown[] = []
    if (patch.title !== undefined) {
      params.push(patch.title)
      sets.push(`title = $${params.length}`)
    }
    if (patch.numberText !== undefined) {
      params.push(patch.numberText)
      sets.push(`number_text = $${params.length}`)
    }
    if (patch.number !== undefined) {
      params.push(patch.number)
      sets.push(`number = $${params.length}`)
    }
    if (!sets.length) return false
    params.push(chapterId)
    const { rowCount } = await this.pool.query(
      `UPDATE chapters SET ${sets.join(', ')} WHERE id = $${params.length}`,
      params
    )
    return (rowCount ?? 0) > 0
  }

  async adminDeleteChapter(chapterId: number) {
    const { rowCount } = await this.pool.query('DELETE FROM chapters WHERE id = $1', [chapterId])
    return (rowCount ?? 0) > 0
  }

  /** Kolom user untuk panel admin (+ status moderasi). */
  private static readonly ADMIN_USER_COLS = `u.id, u.username, u.email, u.display_name AS "displayName", u.bio, u.xp, u.verified, u.premium,
              u.created_at AS "createdAt", CASE WHEN u.avatar IS NULL THEN 0 ELSE u.avatar_v END AS "avatarV",
              u.banned_at AS "bannedAt", u.ban_reason AS "banReason", u.muted_until AS "mutedUntil", u.mute_reason AS "muteReason",
              (SELECT COUNT(*)::int FROM comments c WHERE c.user_id = u.id) AS "commentCount"`

  private adminUser(r: Row, ownerEmails: string[] = []) {
    const muted = r.mutedUntil ? new Date(r.mutedUntil).getTime() : 0
    const email = String(r.email ?? '').toLowerCase()
    return {
      id: Number(r.id),
      username: r.username as string,
      email: (r.email ?? null) as string | null,
      displayName: (r.displayName ?? '') as string,
      bio: (r.bio ?? '') as string,
      xp: Number(r.xp ?? 0),
      level: levelFor(Number(r.xp ?? 0)).level,
      verified: r.verified === true || (!!email && ownerEmails.includes(email)), // owner otomatis terverifikasi
      premium: r.premium === true,
      avatarV: Number(r.avatarV ?? 0),
      hasAvatar: r.avatarV != null && Number(r.avatarV) > 0 && r.hasAvatar !== false,
      commentCount: Number(r.commentCount ?? 0),
      createdAt: r.createdAt ? new Date(r.createdAt).toISOString() : null,
      isOwner: !!email && ownerEmails.includes(email),
      banned: !!r.bannedAt,
      banReason: (r.banReason ?? '') as string,
      mutedUntil: muted > Date.now() ? muted : 0,
      muteReason: (r.muteReason ?? '') as string
    }
  }

  /** `filter`: banned | muted | verified | premium | avatar (punya foto) — kosong = semua. */
  async adminListUsers(opts: { q?: string; filter?: string; sort?: string; limit: number; offset: number; ownerEmails?: string[] }) {
    const params: unknown[] = []
    const where: string[] = []
    if (opts.q) {
      params.push(`%${opts.q.toLowerCase()}%`)
      where.push(`(lower(u.username) LIKE $${params.length} OR lower(COALESCE(u.email,'')) LIKE $${params.length} OR lower(COALESCE(u.display_name,'')) LIKE $${params.length})`)
    }
    if (opts.filter === 'banned') where.push('u.banned_at IS NOT NULL')
    else if (opts.filter === 'muted') where.push('u.muted_until > now()')
    else if (opts.filter === 'verified') where.push('u.verified = true')
    else if (opts.filter === 'premium') where.push('u.premium = true')
    else if (opts.filter === 'avatar') where.push('u.avatar IS NOT NULL')
    else if (opts.filter === 'owner' && opts.ownerEmails?.length) { params.push(opts.ownerEmails); where.push(`lower(u.email) = ANY($${params.length}::text[])`) }
    const w = where.length ? `WHERE ${where.join(' AND ')}` : ''
    params.push(opts.limit, opts.offset)
    const { rows } = await this.pool.query<Row>(
      `SELECT ${PgStore.ADMIN_USER_COLS}, (u.avatar IS NOT NULL) AS "hasAvatar"
       FROM users u ${w}
       ORDER BY ${opts.sort === 'xp' ? 'u.xp DESC, u.id DESC' : 'u.id DESC'}
       LIMIT $${params.length - 1} OFFSET $${params.length}`,
      params
    )
    const countParams = params.slice(0, -2)
    const { rows: cnt } = await this.pool.query<Row>(`SELECT COUNT(*)::int AS n FROM users u ${w}`, countParams)
    return { total: cnt[0]?.n ?? 0, items: rows.map(r => this.adminUser(r, opts.ownerEmails)) }
  }

  async adminGetUser(userId: number, ownerEmails: string[] = []) {
    const { rows } = await this.pool.query<Row>(
      `SELECT ${PgStore.ADMIN_USER_COLS}, (u.avatar IS NOT NULL) AS "hasAvatar" FROM users u WHERE u.id = $1`,
      [userId]
    )
    return rows[0] ? this.adminUser(rows[0], ownerEmails) : null
  }

  /**
   * Moderasi satu user. `muteDays` = lama bisu (hari); `muted: true` tanpa `muteDays` = permanen.
   * Akun owner tidak bisa diblokir/dibisukan. Mengembalikan baris user terbaru, atau null bila tak ada.
   */
  async adminModerateUser(
    userId: number,
    p: { verified?: boolean; premium?: boolean; banned?: boolean; banReason?: string | null; muted?: boolean; muteDays?: number | null; muteReason?: string | null; clearBio?: boolean; clearName?: boolean },
    ownerEmails: string[] = []
  ) {
    const cur = await this.adminGetUser(userId, ownerEmails)
    if (!cur) return null
    if (cur.isOwner && (p.banned === true || p.muted === true)) throw new Error('status: akun owner tidak bisa diblokir atau dibisukan')
    const sets: string[] = []
    const params: unknown[] = [userId]
    const add = (col: string, v: unknown) => { params.push(v); sets.push(`${col} = $${params.length}`) }
    if (p.verified !== undefined) add('verified', p.verified)
    if (p.premium !== undefined) add('premium', p.premium)
    if (p.banned === true) { sets.push('banned_at = now()'); add('ban_reason', (p.banReason ?? '').trim().slice(0, 200) || null) }
    else if (p.banned === false) sets.push('banned_at = NULL', 'ban_reason = NULL')
    if (p.muted === true) {
      if (p.muteDays != null && p.muteDays > 0) { params.push(p.muteDays); sets.push(`muted_until = now() + make_interval(days => $${params.length}::int)`) }
      else sets.push(`muted_until = '9999-12-31T00:00:00Z'`)
      add('mute_reason', (p.muteReason ?? '').trim().slice(0, 200) || null)
    } else if (p.muted === false) sets.push('muted_until = NULL', 'mute_reason = NULL')
    if (p.clearBio) sets.push('bio = NULL')
    if (p.clearName) sets.push('display_name = NULL')
    if (sets.length) await this.pool.query(`UPDATE users SET ${sets.join(', ')} WHERE id = $1`, params)
    return this.adminGetUser(userId, ownerEmails)
  }

  /**
   * Atur XP/level user. Tepat satu dari: `xp` (nilai total), `level` (XP minimum level itu), `delta` (tambah/kurang).
   * Hasil dibatasi 0..XP_MAX. Buku besar `xp_events` (papan peringkat mingguan) tidak diubah.
   */
  async adminSetXp(userId: number, p: { xp?: number; level?: number; delta?: number }, ownerEmails: string[] = []) {
    const given = [p.xp, p.level, p.delta].filter(v => v !== undefined).length
    if (given !== 1) throw new Error('status: isi salah satu: xp, level, atau delta')
    const cur = await this.adminGetUser(userId, ownerEmails)
    if (!cur) return null
    let next: number
    if (p.xp !== undefined) next = p.xp
    else if (p.level !== undefined) next = xpForLevel(p.level)
    else next = cur.xp + (p.delta as number)
    next = Math.max(0, Math.min(XP_MAX, Math.floor(next)))
    await this.pool.query('UPDATE users SET xp = $2 WHERE id = $1', [userId, next])
    return this.adminGetUser(userId, ownerEmails)
  }

  /** Hapus SEMUA pesan Global Chat. Mengembalikan jumlah pesan yang terhapus. */
  async adminClearChat(): Promise<number> {
    const { rowCount } = await this.pool.query('DELETE FROM chat_messages')
    return rowCount ?? 0
  }

  /** Hapus semua pesan Global Chat milik satu user. */
  async adminDeleteUserChat(userId: number): Promise<number> {
    const { rowCount } = await this.pool.query('DELETE FROM chat_messages WHERE user_id = $1', [userId])
    return rowCount ?? 0
  }

  /** Hapus foto profil user (avatar_v dinaikkan supaya cache app/Coil ikut basi). */
  async adminClearUserAvatar(userId: number, ownerEmails: string[] = []) {
    const { rowCount } = await this.pool.query('UPDATE users SET avatar = NULL, avatar_v = avatar_v + 1 WHERE id = $1', [userId])
    return (rowCount ?? 0) > 0 ? this.adminGetUser(userId, ownerEmails) : null
  }

  /** Cabut semua sesi/token satu user (token_version + 1). null bila user tidak ada. */
  async adminRevokeUserSessions(userId: number): Promise<boolean> {
    const { rowCount } = await this.pool.query('UPDATE users SET token_version = token_version + 1 WHERE id = $1', [userId])
    return (rowCount ?? 0) > 0
  }

  /** Cabut semua sesi SEMUA user. `exceptUserId` dilewati supaya admin pemanggil tetap masuk. Mengembalikan jumlah akun terdampak. */
  async adminRevokeAllSessions(exceptUserId?: number): Promise<number> {
    const { rowCount } = await this.pool.query(
      'UPDATE users SET token_version = token_version + 1 WHERE ($1::int IS NULL OR id <> $1::int)',
      [exceptUserId ?? null]
    )
    return rowCount ?? 0
  }

  /** Hapus semua komentar & balasan milik user (suara & balasan orang lain pada komentar itu ikut terhapus). */
  async adminDeleteUserComments(userId: number) {
    const { rowCount } = await this.pool.query('DELETE FROM comments WHERE user_id = $1', [userId])
    return rowCount ?? 0
  }

  /** Semua komentar (termasuk balasan), terbaru dulu. `q` mencari di isi komentar, username, atau nama tampilan. */
  async adminListComments(opts: { q?: string; limit: number; offset: number }) {
    const params: unknown[] = []
    let w = ''
    if (opts.q) {
      params.push(`%${opts.q.toLowerCase()}%`)
      w = `WHERE (lower(c.body) LIKE $1 OR lower(u.username) LIKE $1 OR lower(COALESCE(u.display_name,'')) LIKE $1)`
    }
    params.push(opts.limit, opts.offset)
    const { rows } = await this.pool.query<Row>(
      `SELECT c.id, c.target, c.body, c.parent_id AS "parentId", c.created_at AS "createdAt",
              u.id AS "userId", u.username, u.display_name AS "displayName",
              CASE WHEN u.avatar IS NULL THEN 0 ELSE u.avatar_v END AS "avatarV"
         FROM comments c JOIN users u ON u.id = c.user_id ${w}
        ORDER BY c.id DESC LIMIT $${params.length - 1} OFFSET $${params.length}`,
      params
    )
    const { rows: cnt } = await this.pool.query<Row>(`SELECT COUNT(*)::int AS n FROM comments c JOIN users u ON u.id = c.user_id ${w}`, params.slice(0, -2))
    return {
      total: cnt[0]?.n ?? 0,
      items: rows.map(r => ({
        id: Number(r.id), target: String(r.target), body: String(r.body), parentId: r.parentId == null ? null : Number(r.parentId),
        createdAt: new Date(r.createdAt as string).getTime(), userId: Number(r.userId), username: String(r.username),
        displayName: (r.displayName as string | null) ?? '', avatarV: Number(r.avatarV ?? 0)
      }))
    }
  }

  async adminDeleteComment(commentId: number) {
    const { rowCount } = await this.pool.query('DELETE FROM comments WHERE id = $1', [commentId])
    return (rowCount ?? 0) > 0
  }

  /** Hapus akun permanen (kecuali owner). null = tidak ada; false = akun owner. */
  async adminDeleteUser(userId: number, ownerEmails: string[] = []) {
    const cur = await this.adminGetUser(userId, ownerEmails)
    if (!cur) return null
    if (cur.isOwner) return false
    await this.pool.query('DELETE FROM users WHERE id = $1', [userId])
    return true
  }
}

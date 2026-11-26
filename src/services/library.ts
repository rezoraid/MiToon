import type { Pool } from 'pg'
import { parseId } from '../core/util.js'

const MAX_LIBRARY = 1500
const MAX_READ_IDS = 3000
const PAGE = 500

interface LibraryEntry {
  id: string
  title: string
  cover: string
  provider: string
  bookmarked: boolean
  lastChapterId: string | null
  lastChapterNumber: string | null
  readAt: number
  read: string[]
  cleared: boolean
  updatedAt: number
}

// eslint-disable-next-line no-control-regex
const CTRL = /[\u0000-\u001f\u007f]/g
const clean = (v: unknown, max: number) => [...String(v ?? '').replace(CTRL, '').trim()].slice(0, max).join('')
const num = (v: unknown) => {
  const n = Math.floor(Number(v))
  return Number.isFinite(n) && n > 0 ? Math.min(n, 4_102_444_800_000) : 0
}

export function normalize(raw: unknown): LibraryEntry | null {
  const b = (raw ?? {}) as Record<string, unknown>
  const id = String(b.id ?? '')
  if (!id || id.length > 330) return null
  let provider: string
  try {
    provider = parseId(id).provider
  } catch {
    return null
  }
  const cover = clean(b.cover, 600)
  const read = Array.isArray(b.read) ? [...new Set(b.read.map(x => String(x)).filter(x => x && x.length <= 330))].slice(-MAX_READ_IDS) : []
  const last = clean(b.lastChapterId, 330)
  return {
    id,
    title: clean(b.title, 400),
    cover: /^https?:\/\//i.test(cover) ? cover : '',
    provider,
    bookmarked: b.bookmarked === true,
    lastChapterId: last || null,
    lastChapterNumber: last ? clean(b.lastChapterNumber, 60) || null : null,
    readAt: num(b.readAt),
    read,
    cleared: b.cleared === true,
    updatedAt: num(b.updatedAt)
  }
}

/** Gabungkan dua versi satu seri: bookmark mengikuti yang terbaru diubah; chapter dibaca digabung (kecuali riwayat direset di versi yang lebih baru). */
function merge(a: LibraryEntry, b: LibraryEntry): LibraryEntry {
  const [older, newer] = a.updatedAt > b.updatedAt ? [b, a] : [a, b]
  const reset = newer.cleared
  const latest = reset ? newer : older.readAt > newer.readAt ? older : newer
  const read = reset ? newer.read : [...new Set([...older.read, ...newer.read])].slice(-MAX_READ_IDS)
  return {
    id: newer.id,
    title: newer.title || older.title,
    cover: newer.cover || older.cover,
    provider: newer.provider,
    bookmarked: newer.bookmarked,
    lastChapterId: latest.lastChapterId,
    lastChapterNumber: latest.lastChapterNumber,
    readAt: reset ? newer.readAt : Math.max(older.readAt, newer.readAt),
    read,
    cleared: reset,
    updatedAt: Math.max(older.updatedAt, newer.updatedAt)
  }
}

const fromRow = (r: any): LibraryEntry => ({
  id: String(r.series_id),
  title: String(r.title ?? ''),
  cover: String(r.cover ?? ''),
  provider: String(r.provider ?? ''),
  bookmarked: !!r.bookmarked,
  lastChapterId: r.last_chapter_id ?? null,
  lastChapterNumber: r.last_chapter_number ?? null,
  readAt: Number(r.read_at ?? 0),
  read: (r.read as string[]) ?? [],
  cleared: !!r.cleared,
  updatedAt: Number(r.updated_at ?? 0)
})

/** Sinkronisasi pustaka (bookmark + chapter dibaca) per akun, lintas perangkat. */
export class LibraryService {
  constructor(private readonly pool: Pool) {}

  async sync(userId: number, since: number, incoming: unknown[]): Promise<{ now: number; hasMore: boolean; entries: LibraryEntry[] }> {
    const now = Date.now()
    const items = new Map<string, LibraryEntry>()
    for (const raw of incoming.slice(0, 200)) {
      const e = normalize(raw)
      if (e) items.set(e.id, items.has(e.id) ? merge(items.get(e.id)!, e) : e)
    }
    if (items.size) {
      const client = await this.pool.connect()
      try {
        await client.query('BEGIN')
        const { rows } = await client.query('SELECT * FROM user_library WHERE user_id = $1 AND series_id = ANY($2::text[])', [userId, [...items.keys()]])
        const existing = new Map<string, LibraryEntry>(rows.map((r: any) => [String(r.series_id), fromRow(r)]))
        for (const e of items.values()) {
          const cur = existing.get(e.id)
          const m = cur ? merge(cur, e) : e
          await client.query(
            `INSERT INTO user_library (user_id, series_id, title, cover, provider, bookmarked, last_chapter_id, last_chapter_number, read_at, read, cleared, updated_at, synced_at)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
             ON CONFLICT (user_id, series_id) DO UPDATE SET
               title = EXCLUDED.title, cover = EXCLUDED.cover, provider = EXCLUDED.provider, bookmarked = EXCLUDED.bookmarked,
               last_chapter_id = EXCLUDED.last_chapter_id, last_chapter_number = EXCLUDED.last_chapter_number,
               read_at = EXCLUDED.read_at, read = EXCLUDED.read, cleared = EXCLUDED.cleared, updated_at = EXCLUDED.updated_at, synced_at = EXCLUDED.synced_at`,
            [userId, m.id, m.title, m.cover, m.provider, m.bookmarked, m.lastChapterId, m.lastChapterNumber, m.readAt, m.read, m.cleared, m.updatedAt, now]
          )
        }
        await client.query(
          'DELETE FROM user_library WHERE user_id = $1 AND series_id IN (SELECT series_id FROM user_library WHERE user_id = $1 ORDER BY updated_at DESC OFFSET $2)',
          [userId, MAX_LIBRARY]
        )
        await client.query('COMMIT')
      } catch (err) {
        await client.query('ROLLBACK').catch(() => {})
        throw err
      } finally {
        client.release()
      }
    }
    const { rows } = await this.pool.query('SELECT * FROM user_library WHERE user_id = $1 AND synced_at > $2 ORDER BY synced_at ASC, series_id ASC LIMIT $3', [userId, since, PAGE + 1])
    const hasMore = rows.length > PAGE
    const page = rows.slice(0, PAGE)
    const cursor = hasMore ? Number(page[page.length - 1].synced_at) - 1 : now
    return { now: cursor, hasMore, entries: page.map(fromRow) }
  }
}

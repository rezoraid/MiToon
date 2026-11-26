import type { Pool } from 'pg'
import { parseId } from '../core/util.js'
import { ValidationError } from '../core/errors.js'

/** Maksimal seri yang disimpan per pengguna (yang terlama dibuang): mencegah tabel dibanjiri. */
export const MAX_READING = 200
// eslint-disable-next-line no-control-regex
const CTRL = /[\u0000-\u001f\u007f]/g
const clean = (v: unknown, max: number) => [...String(v ?? '').replace(CTRL, '').replace(/\s+/g, ' ').trim()].slice(0, max).join('')
const clampLimit = (n: number) => Math.min(Math.max(Math.floor(n) || 30, 1), 50)

interface ReadingItem {
  seriesId: string
  title: string
  /** cover diambil dari katalog server (bukan dari kiriman pengguna); '' bila seri belum ada di katalog */
  cover: string
  provider: string
  chapterId: string
  chapterNumber: string
  chaptersRead: number
  /** kapan terakhir dibaca (epoch ms, jam server) */
  readAt: number
}

/** Progres baca per pengguna: satu baris per seri berisi chapter terakhir dibaca. Tampil di profil publik. */
export class ReadingService {
  constructor(private readonly pool: Pool) {}

  /** Simpan/timpa progres sebuah seri. Id divalidasi; chapter harus satu provider dengan seri-nya. */
  async report(userId: number, b: { seriesId?: unknown; chapterId?: unknown; chapterNumber?: unknown; chaptersRead?: unknown; title?: unknown }): Promise<void> {
    const series = String(b.seriesId ?? '')
    const chapter = String(b.chapterId ?? '')
    const s = parseId(series)
    const c = parseId(chapter)
    if (s.provider !== c.provider) throw new ValidationError('Chapter tidak cocok dengan seri')
    const number = clean(b.chapterNumber, 30)
    if (!number) throw new ValidationError('Nomor chapter wajib diisi')
    const read = Math.min(Math.max(Math.floor(Number(b.chaptersRead)) || 1, 1), 100_000)
    await this.pool.query(
      `INSERT INTO reading_progress (user_id, series_id, title, chapter_id, chapter_number, chapters_read, read_at)
       VALUES ($1, $2, $3, $4, $5, $6, now())
       ON CONFLICT (user_id, series_id) DO UPDATE SET
         title = EXCLUDED.title, chapter_id = EXCLUDED.chapter_id, chapter_number = EXCLUDED.chapter_number,
         chapters_read = EXCLUDED.chapters_read, read_at = now()`,
      [userId, series, clean(b.title, 200), chapter, number, read]
    )
    await this.pool.query(
      'DELETE FROM reading_progress WHERE user_id = $1 AND series_id IN (SELECT series_id FROM reading_progress WHERE user_id = $1 ORDER BY read_at DESC OFFSET $2)',
      [userId, MAX_READING]
    )
  }

  /** Daftar baca seorang pengguna, terbaru dulu; lanjut dengan `before` = `readAt` item terakhir yang sudah dimuat. */
  async list(userId: number, limit = 30, before?: number): Promise<{ hasMore: boolean; items: ReadingItem[] }> {
    const n = clampLimit(limit)
    const { rows } = await this.pool.query(
      `SELECT r.series_id, COALESCE(NULLIF(s.title, ''), r.title) AS title, COALESCE(s.cover, '') AS cover,
              split_part(r.series_id, ':', 1) AS provider, r.chapter_id, r.chapter_number, r.chapters_read, r.read_at
         FROM reading_progress r
         LEFT JOIN series s ON s.provider = split_part(r.series_id, ':', 1) AND s.ref = substr(r.series_id, position(':' in r.series_id) + 1)
        WHERE r.user_id = $1 AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = r.user_id AND u.reading_hidden) AND ($2::double precision IS NULL OR r.read_at < to_timestamp($2::double precision / 1000.0))
        ORDER BY r.read_at DESC LIMIT $3`,
      [userId, before ?? null, n + 1]
    )
    return {
      hasMore: rows.length > n,
      items: rows.slice(0, n).map((r: any) => ({
        seriesId: String(r.series_id),
        title: String(r.title ?? ''),
        cover: String(r.cover ?? ''),
        provider: String(r.provider),
        chapterId: String(r.chapter_id),
        chapterNumber: String(r.chapter_number ?? ''),
        chaptersRead: Number(r.chapters_read ?? 0),
        readAt: new Date(r.read_at).getTime()
      }))
    }
  }
}

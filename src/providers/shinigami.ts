import { NotFoundError, UpstreamError } from '../core/errors.js'
import { cid, compact, isoDate, numOrNull, pathSegment, request, retry, sid } from '../core/util.js'
import type { ChapterContent, ProviderAdapter, SeriesDetail, SeriesStatus, SeriesSummary } from '../core/types.js'

const KEY = 'shinigami'
// Domain situs & API Shinigami (ubah di sini bila pindah lagi).
const BASE = 'https://s1.shinigami.mx'
const API = 'https://api.shinigami.io'
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36'
const HEADERS = { 'user-agent': UA, origin: BASE, referer: `${BASE}/`, accept: 'application/json' }
// Entri pengumuman milik situs, bukan komik.
const HIDDEN_TITLES = new Set(['shinigami news'])
const FORMATS = new Set(['manga', 'manhwa', 'manhua'])

type Raw = Record<string, any>

const api = (path: string): Promise<Raw> =>
  retry(async () => {
    const res = await request(API + path, { headers: HEADERS, timeoutMs: 15000 })
    const json: Raw = await res.json()
    if (json.retcode !== undefined && json.retcode !== 0) throw new UpstreamError(json.message || 'API error')
    return json
  })

const names = (list: unknown): string[] =>
  Array.isArray(list) ? list.map(x => x?.name).filter(Boolean) : []
const statusOf = (s: number): SeriesStatus => (({ 1: 'ongoing', 2: 'hiatus' }) as Record<number, SeriesStatus>)[s] || 'completed'

function toSummary(m: Raw): SeriesSummary | null {
  if (!m?.manga_id || !m.title || HIDDEN_TITLES.has(String(m.title).trim().toLowerCase())) return null
  const id = sid(KEY, m.manga_id)
  return {
    id,
    provider: KEY,
    title: m.title,
    cover: m.cover_portrait_url || m.cover_image_url || '',
    url: `${BASE}/series/${m.manga_id}/`,
    type: names(m.taxonomy?.Type)[0]?.toLowerCase() ?? null,
    status: statusOf(m.status),
    genres: names(m.taxonomy?.Genre),
    rating: null,
    chapterCount: null,
    latestChapter: m.latest_chapter_number != null ? String(m.latest_chapter_number) : null,
    sources: [{ provider: KEY, id }]
  }
}

async function list(q: string, page: number, size: number, type?: string): Promise<SeriesSummary[]> {
  const qs = new URLSearchParams({ page: String(page), page_size: String(size), sort: 'latest', sort_order: 'desc' })
  if (q) qs.set('q', q)
  // Tanpa kata kunci = "terbaru": hanya seri yang baru update, seperti halaman utama situs.
  else qs.set('is_update', 'true')
  if (type && FORMATS.has(type.toLowerCase())) qs.set('format', type.toLowerCase())
  const res = await api(`/v1/manga/list?${qs}`)
  return compact((res.data ?? []).map(toSummary))
}

const CHAPTER_LIMIT = 100
const MAX_REQUESTS = 100

async function walkChapters(id: string, order: 'asc' | 'desc', seen: Map<string, Raw>) {
  let cursor = ''
  for (let i = 0; i < MAX_REQUESTS; i++) {
    const qs = new URLSearchParams({ sort_by: 'chapter_number', sort_order: order, limit: String(CHAPTER_LIMIT) })
    if (cursor) qs.set('cursor', cursor)
    let res: Raw
    try {
      res = await api(`/v1/chapter/${pathSegment(id)}/list?${qs}`)
    } catch (e) {
      if (i === 0) throw e
      return
    }
    let fresh = 0
    for (const c of res.data ?? []) {
      const key = c?.chapter_id ? String(c.chapter_id) : ''
      if (key && !seen.has(key)) { seen.set(key, c); fresh++ }
    }
    const next = res.meta?.next_cursor ? String(res.meta.next_cursor) : ''
    if (!next || next === cursor || res.meta?.has_next === false) return
    cursor = next
  }
}

async function chapterList(id: string) {
  const seen = new Map<string, Raw>()
  const results = await Promise.allSettled([walkChapters(id, 'desc', seen), walkChapters(id, 'asc', seen)])
  if (!seen.size) {
    const failed = results.find((r): r is PromiseRejectedResult => r.status === 'rejected')
    if (failed) throw failed.reason
  }
  return [...seen.values()]
    .map((c: Raw) => ({
      id: cid(KEY, c.chapter_id),
      number: String(c.chapter_number ?? ''),
      title: c.chapter_title || '',
      date: isoDate(c.release_date || c.created_at)
    }))
    .sort((a, b) => parseFloat(b.number) - parseFloat(a.number))
}

export function createShinigami(): ProviderAdapter {
  return {
    key: KEY,
    name: 'Shinigami',
    baseUrl: BASE,

    search: (q, { page, limit }) => list(q, page, limit),
    latest: ({ page, limit, type }) => list('', page, limit, type),

    async detail(ref): Promise<SeriesDetail> {
      const [res, chapters] = await Promise.all([api(`/v1/manga/detail/${pathSegment(ref)}`), chapterList(ref)])
      const d: Raw | undefined = res.data
      if (!d) throw new NotFoundError('Manga tidak ditemukan')
      const base = toSummary({ ...d, manga_id: d.manga_id ?? ref })
      if (!base) throw new NotFoundError('Manga tidak ditemukan')
      return {
        ...base,
        chapterCount: numOrNull(chapters.length),
        latestChapter: base.latestChapter ?? chapters[0]?.number ?? null,
        synopsis: d.description || '',
        author: names(d.taxonomy?.Author).join(', '),
        artist: names(d.taxonomy?.Artist).join(', '),
        year: numOrNull(d.release_year),
        chapters
      }
    },

    async chapter(ref): Promise<ChapterContent> {
      const d: Raw | undefined = (await api(`/v1/chapter/detail/${pathSegment(ref)}`)).data
      if (!d) throw new NotFoundError('Chapter tidak ditemukan')
      const ch: Raw = d.chapter || {}
      const root = d.base_url || ch.base_url || ''
      const join = (...p: unknown[]) =>
        p
          .map(s => String(s || '').replace(/^\/+|\/+$/g, ''))
          .filter(Boolean)
          .join('/')
      return {
        id: cid(KEY, d.chapter_id || ref),
        seriesId: d.manga_id ? sid(KEY, d.manga_id) : null,
        number: String(d.chapter_number ?? ''),
        images: (ch.data ?? []).map((f: string) => (/^https?:\/\//.test(f) ? f : join(root, ch.path, f))),
        prev: d.prev_chapter_id ? cid(KEY, d.prev_chapter_id) : null,
        next: d.next_chapter_id ? cid(KEY, d.next_chapter_id) : null,
        imageHeaders: this.imageHeaders()
      }
    },

    imageHeaders: () => ({ 'user-agent': UA, referer: `${BASE}/`, origin: BASE })
  }
}

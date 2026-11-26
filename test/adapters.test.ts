/** Adapter diuji dengan fetch ter-mock; bentuk payload mengikuti field yang dibaca scraper asli. */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { createLumos } from '../src/providers/lumos.js'
import { createShinigami } from '../src/providers/shinigami.js'
import { mockFetch } from './helpers.js'

describe('shinigami', () => {
  const manga = { manga_id: 'abc', title: 'Shini', status: 1, cover_portrait_url: 'p.jpg', latest_chapter_number: 12, taxonomy: { Type: [{ name: 'Manhwa' }], Genre: [{ name: 'Drama' }], Author: [{ name: 'Au' }] } }

  it('search/latest/detail (chapter list multi-halaman)/chapter', async () => {
    const m = mockFetch({
      '/v1/manga/list': () => ({ json: { retcode: 0, data: [manga, { manga_id: '', title: 'x' }, { manga_id: 'n1', title: 'Shinigami News' }] } }),
      '/v1/manga/detail/abc': () => ({ json: { retcode: 0, data: { ...manga, description: 'desc', release_year: 2021 } } }),
      'cursor=cur2': () => ({ json: { retcode: 0, meta: { has_next: false }, data: [{ chapter_id: 'c2', chapter_number: 2 }] } }),
      'sort_order=asc': () => ({ json: { retcode: 0, meta: { has_next: false }, data: [{ chapter_id: 'c1', chapter_number: 1, release_date: '2024-02-01T00:00:00Z' }] } }),
      'limit=100': () => ({ json: { retcode: 0, meta: { has_next: true, next_cursor: 'cur2' }, data: [{ chapter_id: 'c1', chapter_number: 1, release_date: '2024-02-01T00:00:00Z' }] } }),
      '/v1/chapter/detail/c2': () => ({ json: { retcode: 0, data: { chapter_id: 'c2', manga_id: 'abc', chapter_number: 2, prev_chapter_id: 'c1', chapter: { base_url: 'https://cdn/', path: '/p/', data: ['1.jpg', 'https://o/2.jpg'] } } } })
    })
    try {
      const a = createShinigami()
      const found = await a.search('shini', { page: 1, limit: 10 })
      const [s] = found
      assert.equal(s.id, 'shinigami:abc')
      assert.equal(found.length, 1) // 'Shinigami News' & id kosong disaring
      assert.equal(s.type, 'manhwa')
      assert.equal(s.status, 'ongoing')
      assert.equal(s.latestChapter, '12')
      assert.ok(m.calls[0].startsWith('https://api.shinigami.io/v1/manga/list'))
      assert.ok(m.calls[0].includes('q=shini') && m.calls[0].includes('page_size=10'))
      assert.equal((await a.latest({ page: 1, limit: 10, type: 'manhwa' })).length, 1)
      assert.ok(m.calls[1].includes('is_update=true') && m.calls[1].includes('format=manhwa') && !m.calls[1].includes('q='))
      const d = await a.detail('abc')
      assert.deepEqual(d.chapters.map(c => c.id), ['shinigami:c2', 'shinigami:c1'])
      assert.equal(d.synopsis, 'desc')
      assert.equal(d.year, 2021)
      assert.equal(d.chapterCount, 2)
      const ch = await a.chapter('c2')
      assert.deepEqual(ch.images, ['https://cdn/p/1.jpg', 'https://o/2.jpg'])
      assert.equal(ch.seriesId, 'shinigami:abc')
      assert.equal(ch.prev, 'shinigami:c1')
      assert.equal(ch.next, null)
    } finally { m.restore() }
  })

  it('chapter list: limit=100, ikuti cursor desc + asc lalu digabung', async () => {
    const row = (n: number) => ({ chapter_id: `c${n}`, chapter_number: n })
    const m = mockFetch({
      '/v1/manga/detail/abc': () => ({ json: { retcode: 0, data: { ...manga } } }),
      'cursor=k2': () => ({ json: { retcode: 0, meta: { has_next: false }, data: [row(1), row(2)] } }),
      'cursor=k1': () => ({ json: { retcode: 0, meta: { has_next: true, next_cursor: 'k2' }, data: [row(3), row(4), row(5)] } }),
      'sort_order=asc': () => ({ json: { retcode: 0, meta: { has_next: false }, data: [row(1), row(2)] } }),
      'limit=100': () => ({ json: { retcode: 0, meta: { has_next: true, next_cursor: 'k1' }, data: [row(6), row(7), row(8)] } })
    })
    try {
      const d = await createShinigami().detail('abc')
      assert.deepEqual(d.chapters.map(c => c.number), ['8', '7', '6', '5', '4', '3', '2', '1'])
      assert.ok(m.calls.some(u => u.includes('/chapter/abc/list') && u.includes('limit=100')))
      assert.ok(!m.calls.some(u => u.includes('page_size')))
    } finally { m.restore() }
  })

  it('retcode != 0 -> error (setelah retry)', async () => {
    const m = mockFetch({ '/v1/manga/list': () => ({ json: { retcode: 5, message: 'bad' } }) })
    try {
      await assert.rejects(createShinigami().latest({ page: 1, limit: 5 }), /bad/)
      assert.equal(m.calls.length, 3)
    } finally { m.restore() }
  })
})

describe('lumos (parser mengikuti scraper terbaru)', () => {
  const b64 = (u: string) => Buffer.from(u).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

  it('latest, search (+fallback), rotasi mirror, thumbnail base64', async () => {
    let first = true
    const m = mockFetch({
      '/api/home-latest': () => (first ? ((first = false), { status: 500 }) : { json: { data: { items: [{ slug: 's1', title: 'Lumos T', coverImage: `/api/image/thumbnail?src=${b64('https://cdn.x/c.webp')}`, type: 'Manhwa', status: 'Ongoing', rating: '4.5', recentChapters: [{ number: 3 }, { number: 10 }] }] } } }),
      '/api/search': () => ({ json: { results: [{ slug: 's1', title: 'Lumos T' }, { title: 'tanpa slug' }] } })
    })
    try {
      const a = createLumos()
      const [i] = await a.latest({ page: 1, limit: 10 })
      assert.ok(m.calls[1].startsWith('https://02.lumosgg.com'), 'mirror berikutnya dipakai')
      assert.equal(i.id, 'lumos:s1')
      assert.equal(i.latestChapter, '10')
      assert.equal(i.type, 'manhwa')
      assert.equal(i.status, 'ongoing')
      assert.equal(i.cover, 'https://cdn.x/c.webp')
      assert.equal((await a.search('lumos', { page: 1, limit: 10 })).length, 1)
    } finally { m.restore() }
  })

  const browseHtml = `<div id="bf"><label data-bf-genre-name="slice of life">Slice of Life</label><label data-bf-genre-name="sci-fi">Sci-Fi</label></div>`
  const cards = `<div class="htg-card x"><a href="/comic/abc"><h3>Judul &amp; A</h3></a><img src="/c/abc.jpg"><svg></svg> 4.7 </span><span class="capitalize">Ongoing</span><span class="text-surface-300"> 25</span></div>
    <div class="htg-card x"><a href="/comic/def"><h3>B</h3></a></div>`

  it('browse: genre/sort/type dipetakan ke query Lumos & kartu diparse', async () => {
    const m = mockFetch({ '/browse?sort=': () => ({ text: cards }), '/browse?page=': () => ({ text: cards }), '/browse': () => ({ text: browseHtml }) })
    try {
      const a = createLumos()
      const items = await a.browse!({ page: 2, sort: 'popular', genre: 'sci-fi', type: 'manhwa', status: 'ongoing' })
      const url = m.calls.at(-1)!
      assert.ok(url.includes('page=2') && url.includes('sort=popular') && url.includes('type=manhwa') && url.includes('status=ongoing') && url.includes('genre=sci-fi'), url)
      assert.equal(items.length, 2)
      assert.deepEqual([items[0].id, items[0].title, items[0].rating, items[0].status, items[0].chapterCount], ['lumos:abc', 'Judul & A', 4.7, 'ongoing', 25])
      assert.deepEqual(items[0].genres, ['Sci-Fi'])
      assert.equal(items[0].type, 'manhwa')
      assert.deepEqual(await a.browse!({ page: 1, sort: 'latest', genre: 'tidak-ada' }), [])
      assert.deepEqual(await a.listGenres!(), ['Slice of Life', 'Sci-Fi'])
      assert.ok((await a.browse!({ page: 1, sort: 'latest', genre: 'slice-of-life' })).length)
      assert.ok(m.calls.at(-1)!.includes('genre=slice+of+life'))
    } finally { m.restore() }
  })

  it('detail & chapter dari HTML', async () => {
    const detailHtml = `<html><h1>Lumos T</h1><div>Status</div><span class="capitalize">Ongoing</span><div>Type</div><div class="text-sm">Manhwa</div>
      <a href="/browse?genre=action">Action</a><a href="/browse?genre=boys'%20love">Boys&#39; Love</a><span class="rating-value">4.2</span>
      <a href="/read/s1/chapter-2" class="x" data-chapter="2"><span class="text-heading">Judul</span></a>
      <a href="/read/s1/chapter-10" class="x" data-chapter="10">x</a><a href="/read/other/chapter-99" data-chapter="99">x</a>
      <div data-sr="${Buffer.from('Sinopsis').toString('base64')}"></div></html>`
    const readHtml = `<script>const chapterNumber = "10";</script>
      <img alt="Page 2" src="/file/comic/s1/10/2.jpg"><img src="/file/comic/s1/10/1.jpg" alt="Page 1">
      <a href="/read/s1/chapter-9">‹ Prev</a><a href="/read/s1/chapter-11">Next ›</a><a href="/read/s1/chapter-3">Chapter 3</a>`
    const m = mockFetch({ '/comic/s1': () => ({ text: detailHtml }), '/read/s1/chapter-10': () => ({ text: readHtml }) })
    try {
      const a = createLumos()
      const d = await a.detail('s1')
      assert.deepEqual(d.chapters.map(c => c.number), ['10', '2'])
      assert.equal(d.chapters[0].id, 'lumos:s1:chapter-10')
      assert.equal(d.chapters[1].title, 'Judul')
      assert.deepEqual(d.genres, ['Action', "Boys' Love"])
      assert.equal(d.synopsis, 'Sinopsis')
      assert.equal(d.rating, 4.2)
      assert.equal(d.status, 'ongoing')
      assert.equal(d.type, 'manhwa')
      const ch = await a.chapter('s1:chapter-10')
      assert.equal(ch.images.length, 2)
      assert.ok(ch.images[0].endsWith('/1.jpg'), 'urut halaman')
      assert.equal(ch.prev, 'lumos:s1:chapter-9')
      assert.equal(ch.next, 'lumos:s1:chapter-11')
      assert.equal(ch.number, '10')
    } finally { m.restore() }
  })
})

describe('lumos (ketahanan: mirror lambat/diblokir)', () => {
  /** fetch palsu per-host: handler boleh menggantung (hormati abort) atau membalas. */
  function hostFetch(h: Record<string, (url: string, signal?: AbortSignal | null) => Promise<Response> | Response>) {
    const calls: string[] = []
    const original = globalThis.fetch
    globalThis.fetch = (async (input: any, init?: any) => {
      const url = String(input)
      calls.push(url)
      const host = Object.keys(h).find(k => url.startsWith(k))
      if (!host) return new Response('no route', { status: 599 })
      return h[host](url, init?.signal)
    }) as typeof fetch
    return { calls, restore: () => void (globalThis.fetch = original) }
  }
  const hang = (_u: string, signal?: AbortSignal | null) =>
    new Promise<Response>((_, rej) => signal?.addEventListener('abort', () => rej(signal.reason ?? new DOMException('Aborted', 'AbortError'))))
  const json = (v: unknown) => new Response(JSON.stringify(v), { status: 200 })
  const items = { results: [{ slug: 's1', title: 'Lumos T' }] }

  it('mirror pertama menggantung -> mirror kedua menjawab dalam < 4 detik', async () => {
    const m = hostFetch({ 'https://03.lumosgg.com': hang, 'https://02.lumosgg.com': () => json(items) })
    try {
      const t0 = Date.now()
      const r = await createLumos().search('x', { page: 1, limit: 5 })
      assert.equal(r.length, 1)
      assert.ok(Date.now() - t0 < 4000, 'tidak menunggu timeout mirror pertama')
    } finally { m.restore() }
  })

  it('halaman tantangan Cloudflare (200) dianggap gagal -> pindah mirror', async () => {
    const m = hostFetch({
      'https://03.lumosgg.com': () => new Response('<html><title>Just a moment...</title></html>', { status: 200 }),
      'https://02.lumosgg.com': () => json(items)
    })
    try {
      assert.equal((await createLumos().search('x', { page: 1, limit: 5 })).length, 1)
    } finally { m.restore() }
  })

  it('API search mati -> cadangan katalog /browse', async () => {
    const card = '<div class="htg-card"><a href="/comic/abc"><h3>Judul</h3></a></div>'
    const m = hostFetch({ 'https://': url => (url.includes('/api/') ? new Response('x', { status: 403 }) : new Response(card, { status: 200 })) })
    try {
      const r = await createLumos().search('judul', { page: 1, limit: 5 })
      assert.deepEqual(r.map(x => x.id), ['lumos:abc'])
    } finally { m.restore() }
  })

  it('semua mirror gagal -> error menyebut penyebab per mirror (bukan timeout samar)', async () => {
    const m = hostFetch({ 'https://': () => new Response('forbidden', { status: 403 }) })
    try {
      await assert.rejects(createLumos().latest({ page: 1, limit: 5 }), (e: any) => {
        assert.equal(e.status, 502)
        assert.match(e.message, /03\.lumosgg\.com: HTTP 403/)
        return true
      })
    } finally { m.restore() }
  })

  it('404 di semua mirror = NotFound (bukan 502)', async () => {
    const m = hostFetch({ 'https://': () => new Response('nope', { status: 404 }) })
    try {
      await assert.rejects(createLumos().detail('tidak-ada'), (e: any) => e.status === 404)
    } finally { m.restore() }
  })
})

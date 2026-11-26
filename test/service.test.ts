import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { Cache } from '../src/core/cache.js'
import { UpstreamError } from '../src/core/errors.js'
import type { ProviderAdapter, ProviderKey, SeriesDetail, SeriesSummary, Store } from '../src/core/types.js'
import { ScraperService, settleWithGrace } from '../src/services/scraper.js'
import { MemoryKV, noopLog } from './helpers.js'

const item = (p: ProviderKey, ref: string, title: string): SeriesSummary => ({
  id: `${p}:${ref}`, provider: p, title, cover: '', url: '', type: null, status: null, genres: [], rating: null,
  chapterCount: null, latestChapter: null, sources: [{ provider: p, id: `${p}:${ref}` }]
})
const detailOf = (p: ProviderKey, ref: string, title: string, nums: string[]): SeriesDetail => ({
  ...item(p, ref, title), synopsis: '', author: '', artist: '', year: null,
  chapters: nums.map(n => ({ id: `${p}:${ref}:${n}`, number: n, title: '', date: null }))
})

function adapter(key: ProviderKey, o: { fail?: boolean; titles?: string[] } = {}) {
  const state = { fail: !!o.fail, calls: 0 }
  const guard = async <T>(v: T): Promise<T> => {
    state.calls++
    if (state.fail) throw new UpstreamError(`${key} down`)
    return v
  }
  const titles = o.titles ?? ['Solo Leveling']
  const a: ProviderAdapter = {
    key, name: key, baseUrl: '',
    search: () => guard(titles.map((t, i) => item(key, `${i}`, t))),
    latest: () => guard(titles.map((t, i) => item(key, `${i}`, t))),
    detail: ref => guard(detailOf(key, ref, titles[0], ['2', '1'])),
    chapter: ref => guard({ id: `${key}:${ref}`, seriesId: null, number: '1', images: [`${key}.jpg`], prev: null, next: null, imageHeaders: {} }),
    imageHeaders: () => ({})
  }
  return { a, state }
}

function fakeStore(over: Partial<Store> = {}): Store {
  const noop = async () => undefined
  return {
    syncProviders: noop, saveSummaries: noop, saveDetail: noop, addViews: noop, prune: noop,
    detailLocal: async () => null, findSeries: async () => null, siblings: async () => [], findChapter: async () => null, chapterLocal: async () => null, saveChapterImages: async () => {},
    searchLocal: async () => [], latestLocal: async () => [], popular: async () => [], recommended: async () => [], genres: async () => [], byGenre: async () => [],
    needingDetail: async () => [], ping: async () => true, ...over
  }
}

const cfg = {
  ttl: { search: { fresh: 1000, stale: 5000 }, latest: { fresh: 1000, stale: 5000 }, detail: { fresh: 1000, stale: 5000 }, chapter: { fresh: 1000, stale: 5000 }, db: { fresh: 1000, stale: 5000 }, genres: { fresh: 1000, stale: 5000 } },
  aggregateTimeoutMs: 500, primaryTimeoutMs: 500
}
const make = (adapters: ProviderAdapter[], store = fakeStore(), conf: ConstructorParameters<typeof ScraperService>[0]['cfg'] = cfg) => {
  const hits: string[] = []
  const svc = new ScraperService({ adapters, cache: new Cache(new MemoryKV(), noopLog), store, views: { hit: (p, r) => void hits.push(`${p}:${r}`) }, log: noopLog, cfg: conf })
  return { svc, hits }
}
const q = { page: 1, limit: 10 }

describe('ScraperService list', () => {
  it('menggabungkan provider & dedupe judul sama (sources terkumpul)', async () => {
    const { svc } = make([adapter('shinigami').a, adapter('lumos', { titles: ['Solo-Leveling', 'Other'] }).a])
    const r = await svc.search('solo', q)
    assert.deepEqual(r.data.map(d => d.title), ['Solo Leveling', 'Other'])
    assert.deepEqual(r.data[0].sources.map(s => s.id), ['shinigami:0', 'lumos:0'])
    assert.equal(r.meta.degraded, false)
    assert.equal(r.meta.source, 'upstream')
    assert.equal((await svc.search('solo', q)).meta.source, 'cache')
  })

  it('satu provider gagal -> hasil provider lain, degraded', async () => {
    const { svc } = make([adapter('shinigami', { fail: true }).a, adapter('lumos').a])
    const r = await svc.latest(q)
    assert.equal(r.data.length, 1)
    assert.equal(r.meta.degraded, true)
    assert.deepEqual(r.meta.providers, { shinigami: 'error', lumos: 'ok' })
  })

  it('semua gagal -> data dari DB; DB kosong -> 502', async () => {
    const down = () => [adapter('shinigami', { fail: true }).a, adapter('lumos', { fail: true }).a]
    const withDb = make(down(), fakeStore({ searchLocal: async () => [item('lumos', '1', 'Dari DB')] }))
    const r = await withDb.svc.search('x', q)
    assert.equal(r.meta.source, 'db')
    assert.equal(r.data[0].title, 'Dari DB')
    await assert.rejects(make(down()).svc.search('x', q), { code: 'UPSTREAM_ERROR' })
  })

  it('circuit breaker terbuka setelah 5 kegagalan -> provider dilewati cepat', async () => {
    const bad = adapter('shinigami', { fail: true })
    const { svc } = make([bad.a, adapter('lumos').a])
    for (let i = 1; i <= 6; i++) await svc.search(`q${i}`, q)
    assert.equal(bad.state.calls, 5)
    assert.equal(svc.providers()[0].circuit, 'open')
    assert.equal((await svc.search('q7', q)).meta.providers.shinigami, 'unavailable')
  })

  it('provider= membatasi ke satu provider', async () => {
    const m = adapter('lumos')
    const { svc } = make([adapter('shinigami').a, m.a])
    const r = await svc.latest({ ...q, provider: 'lumos' })
    assert.deepEqual(Object.keys(r.meta.providers), ['lumos'])
  })
})

describe('ScraperService detail/chapter fallback', () => {
  it('detail: provider asal gagal -> sibling dari DB', async () => {
    const store = fakeStore({
      findSeries: async () => ({ title: 'Solo Leveling', normTitle: 'sololeveling' }),
      siblings: async () => [{ provider: 'lumos', ref: '77' }]
    })
    const { svc, hits } = make([adapter('shinigami', { fail: true }).a, adapter('lumos').a], store)
    const r = await svc.detail('shinigami:abc')
    assert.equal(r.meta.fallback, true)
    assert.equal(r.meta.provider, 'lumos')
    assert.equal(r.meta.requestedId, 'shinigami:abc')
    assert.equal(r.data.id, 'lumos:77')
    assert.deepEqual(hits, ['lumos:77'])
  })

  it('detail: tanpa sibling di DB -> cari via search provider lain', async () => {
    const store = fakeStore({ findSeries: async () => ({ title: 'Solo Leveling', normTitle: 'sololeveling' }) })
    const { svc } = make([adapter('shinigami', { fail: true }).a, adapter('lumos', { titles: ['solo leveling'] }).a], store)
    const r = await svc.detail('shinigami:abc')
    assert.equal(r.meta.fallback, true)
    assert.equal(r.data.id, 'lumos:0')
  })

  it('detail: gagal & tidak ada pengganti -> error asli; id tidak valid -> 400', async () => {
    const { svc } = make([adapter('shinigami', { fail: true }).a, adapter('lumos').a])
    await assert.rejects(svc.detail('shinigami:abc'), { code: 'UPSTREAM_ERROR' })
    await assert.rejects(svc.detail('nonsense'), { code: 'BAD_REQUEST' })
    await assert.rejects(make([adapter('shinigami').a]).svc.detail('lumos:abc'), { code: 'BAD_REQUEST' }) // provider tidak aktif
  })

  it('detail: data stale dipakai saat upstream gagal', async () => {
    const a = adapter('lumos')
    const short = { ...cfg, ttl: { ...cfg.ttl, detail: { fresh: 5, stale: 5000 } } }
    const svc = new ScraperService({ adapters: [a.a], cache: new Cache(new MemoryKV(), noopLog), store: fakeStore(), views: { hit() {} }, log: noopLog, cfg: short })
    await svc.detail('lumos:1')
    await new Promise(r => setTimeout(r, 15))
    a.state.fail = true
    const r = await svc.detail('lumos:1')
    assert.equal(r.meta.source, 'stale')
    assert.equal(r.data.id, 'lumos:1')
  })

  it('chapter: fallback mencocokkan nomor chapter di provider lain', async () => {
    const store = fakeStore({
      findChapter: async () => ({ seriesRef: 'abc', number: '2' }),
      findSeries: async () => ({ title: 'Solo Leveling', normTitle: 'sololeveling' }),
      siblings: async () => [{ provider: 'lumos', ref: '77' }]
    })
    const { svc } = make([adapter('shinigami', { fail: true }).a, adapter('lumos').a], store)
    const r = await svc.chapter('shinigami:c2')
    assert.equal(r.meta.fallback, true)
    assert.equal(r.data.id, 'lumos:77:2')
    assert.deepEqual(r.data.images, ['lumos.jpg'])
  })

  it('chapter: tanpa jejak di DB -> error asli', async () => {
    const { svc } = make([adapter('shinigami', { fail: true }).a, adapter('lumos').a])
    await assert.rejects(svc.chapter('shinigami:c2'), { code: 'UPSTREAM_ERROR' })
  })
})

describe('ScraperService refresh manual', () => {
  it('refresh=true melewati cache & tidak menghitung view; dibatasi 1x/menit per id', async () => {
    const a = adapter('lumos')
    const { svc, hits } = make([a.a])
    await svc.detail('lumos:1')
    assert.equal(a.state.calls, 1)
    await svc.detail('lumos:1')
    assert.equal(a.state.calls, 1, 'tanpa refresh dari cache')
    const r = await svc.detail('lumos:1', true)
    assert.equal(a.state.calls, 2, 'refresh mengambil ulang dari provider')
    assert.equal(r.meta.source, 'upstream')
    assert.equal(hits.length, 2, 'refresh tidak menambah view')
    await svc.detail('lumos:1', true)
    assert.equal(a.state.calls, 2, 'refresh kedua dalam semenit dilayani dari cache')
    await svc.chapter('lumos:1:1')
    await svc.chapter('lumos:1:1', true)
    assert.equal(a.state.calls, 4)
  })
})

describe('ScraperService latest: filter type', () => {
  it('type tidak dikirim ke upstream; semua provider difilter dari item.type', async () => {
    const seen: string[] = []
    const withTypes = (p: ProviderKey, types: (string | null)[]) => {
      const { a } = adapter(p)
      a.latest = async o => {
        seen.push(`${p}:${o.type ?? ''}`)
        return types.map((t, i) => ({ ...item(p, `${i}`, `${p}-${i}`), type: t }))
      }
      return a
    }
    const { svc } = make([withTypes('shinigami', ['manga', 'manhwa', null]), withTypes('lumos', ['manga', 'manga'])])
    const r = await svc.latest({ ...q, type: 'manga' })
    assert.deepEqual(r.data.map(d => d.id).sort(), ['lumos:0', 'lumos:1', 'shinigami:0'])
    assert.deepEqual(seen.sort(), ['lumos:', 'shinigami:'])
  })

  it('tanpa type: semua item lolos', async () => {
    const { svc } = make([adapter('shinigami', { titles: ['A', 'B'] }).a])
    const r = await svc.latest(q)
    assert.equal(r.data.length, 2)
  })
})

const wait = (ms: number) => new Promise(r => setTimeout(r, ms))

describe('settleWithGrace', () => {
  const task = (v: string, ms: number) => wait(ms).then(() => v)
  it('setelah satu task bagus selesai, sisanya hanya ditunggu grace lalu diganti timedOut', async () => {
    const t0 = Date.now()
    const r = await settleWithGrace([task('cepat', 5), task('lambat', 400)], 30, () => true, i => `timeout${i}`)
    assert.deepEqual(r, ['cepat', 'timeout1'])
    assert.ok(Date.now() - t0 < 300)
  })
  it('graceMs 0 = tunggu semua; task "tidak bagus" tidak memulai grace', async () => {
    assert.deepEqual(await settleWithGrace([task('a', 5), task('b', 60)], 0, () => true, () => 'x'), ['a', 'b'])
    assert.deepEqual(await settleWithGrace([task('', 5), task('b', 60)], 10, v => v !== '', () => 'x'), ['', 'b'])
    assert.deepEqual(await settleWithGrace([], 10, () => true, () => 'x'), [])
  })
})

describe('ScraperService: list grace', () => {
  const slow = (key: ProviderKey, ms: number, titles: string[]) => {
    const { a } = adapter(key, { titles })
    const base = a.latest
    a.latest = async o => (await wait(ms), base(o))
    return a
  }
  const conf = { ...cfg, aggregateTimeoutMs: 2000, listGraceMs: 30 }

  it('provider lambat tidak menahan respons; request berikutnya sudah lengkap dari cache', async () => {
    const { svc } = make([adapter('shinigami', { titles: ['Cepat'] }).a, slow('lumos', 250, ['Lambat'])], fakeStore(), conf)
    const t0 = Date.now()
    const r1 = await svc.latest(q)
    assert.ok(Date.now() - t0 < 200, 'tidak menunggu provider lambat')
    assert.deepEqual(r1.data.map(d => d.title), ['Cepat'])
    assert.deepEqual(r1.meta.providers, { shinigami: 'ok', lumos: 'timeout' })
    assert.equal(r1.meta.degraded, true)
    await wait(300)
    const r2 = await svc.latest(q)
    assert.deepEqual(r2.data.map(d => d.title).sort(), ['Cepat', 'Lambat'])
    assert.equal(r2.meta.source, 'cache')
  })

  it('provider cepat yang hasilnya kosong tidak memotong provider lain', async () => {
    const empty = adapter('shinigami').a
    empty.latest = async () => []
    const { svc } = make([empty, slow('lumos', 100, ['Ada'])], fakeStore(), conf)
    const r = await svc.latest(q)
    assert.deepEqual(r.data.map(d => d.title), ['Ada'])
  })
})

describe('ScraperService: detail dari PostgreSQL saat cache kosong', () => {
  const stored = detailOf('lumos', '1', 'Tersimpan', ['3', '2', '1'])
  const slowDetail = () => {
    const m = adapter('lumos', { titles: ['Terbaru'] })
    const base = m.a.detail
    m.a.detail = async ref => (await wait(120), base(ref))
    return m
  }

  it('dilayani langsung dari DB (source=db), lalu cache terisi data terbaru', async () => {
    const m = slowDetail()
    const { svc, hits } = make([m.a], fakeStore({ detailLocal: async () => stored }))
    const t0 = Date.now()
    const r1 = await svc.detail('lumos:1')
    assert.ok(Date.now() - t0 < 100, 'tidak menunggu upstream')
    assert.equal(r1.meta.source, 'db')
    assert.equal(r1.data.title, 'Tersimpan')
    assert.deepEqual(hits, ['lumos:1'])
    await wait(200)
    const r2 = await svc.detail('lumos:1')
    assert.equal(r2.meta.source, 'cache')
    assert.equal(r2.data.title, 'Terbaru')
  })

  it('upstream gagal + ada data DB -> tetap tampil; tanpa data DB -> error', async () => {
    const m = adapter('lumos', { fail: true })
    assert.equal((await make([m.a], fakeStore({ detailLocal: async () => stored })).svc.detail('lumos:1')).meta.source, 'db')
    await assert.rejects(make([adapter('lumos', { fail: true }).a]).svc.detail('lumos:1'), { code: 'UPSTREAM_ERROR' })
  })

  it('DB bermasalah -> diabaikan, lanjut ke upstream; serverless tidak memakai fallback', async () => {
    const broken = fakeStore({ detailLocal: async () => { throw new Error('db down') } })
    assert.equal((await make([adapter('lumos').a], broken).svc.detail('lumos:1')).meta.source, 'upstream')
    const m = adapter('lumos', { titles: ['Terbaru'] })
    const r = await make([m.a], fakeStore({ detailLocal: async () => stored }), { ...cfg, serverless: true }).svc.detail('lumos:1')
    assert.equal(r.meta.source, 'upstream')
    assert.equal(r.data.title, 'Terbaru')
  })
})

describe('ScraperService: prefetch chapter berikutnya', () => {
  const reader = (flag?: boolean) => {
    const asked: string[] = []
    const m = adapter('lumos')
    m.a.chapter = async ref => {
      asked.push(ref)
      const n = Number(ref.split(':')[1])
      return { id: `lumos:${ref}`, seriesId: null, number: String(n), images: ['x.jpg'], prev: null, next: n < 3 ? `lumos:s:${n + 1}` : null, imageHeaders: {} }
    }
    const conf = flag === undefined ? cfg : { ...cfg, prefetchNextChapter: flag }
    return { asked, svc: make([m.a], fakeStore(), conf).svc }
  }

  it('membuka chapter 1 -> chapter 2 diambil di background -> dibuka berikutnya dari cache', async () => {
    const { asked, svc } = reader()
    await svc.chapter('lumos:s:1')
    await wait(30)
    assert.deepEqual(asked, ['s:1', 's:2'])
    const r = await svc.chapter('lumos:s:2')
    assert.equal(r.meta.source, 'cache')
  })

  it('bisa dimatikan lewat config; chapter terakhir tidak memicu apa-apa', async () => {
    const off = reader(false)
    await off.svc.chapter('lumos:s:1')
    await wait(30)
    assert.deepEqual(off.asked, ['s:1'])
    const last = reader()
    await last.svc.chapter('lumos:s:3')
    await wait(30)
    assert.deepEqual(last.asked, ['s:3'])
  })
})

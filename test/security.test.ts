import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { signToken, verifyToken } from '../src/core/auth.js'
import { AuthService } from '../src/services/auth.js'
import { Cache } from '../src/core/cache.js'
import { NotFoundError } from '../src/core/errors.js'
import { applySecurityHeaders, parseTrustProxy } from '../src/core/security.js'
import type { ProviderAdapter, Store } from '../src/core/types.js'
import { parseId } from '../src/core/util.js'
import { config } from '../src/config.js'
import { createLumos } from '../src/providers/lumos.js'
import { createShinigami } from '../src/providers/shinigami.js'
import { CommentService } from '../src/services/comments.js'
import { ScraperService } from '../src/services/scraper.js'
import { XpService } from '../src/services/xp.js'
import { MemoryKV, mockFetch, noopLog } from './helpers.js'

const TRAVERSAL = ['shinigami:..', 'shinigami:.', 'lumos:..', 'shinigami:%2e%2e', 'shinigami:%2E%2E', 'lumos:%2e', 'shinigami:a%2fb', 'shinigami:a%5Cb', 'lumos:a%2Fb:c']

describe('path traversal ke upstream', () => {
  it('parseId menolak dot-segment dan encoding pemisah path', () => {
    for (const id of TRAVERSAL) assert.throws(() => parseId(id), { code: 'BAD_REQUEST' }, id)
    assert.deepEqual(parseId('lumos:solo-leveling:chapter-1.5'), { provider: 'lumos', ref: 'solo-leveling:chapter-1.5' })
    assert.deepEqual(parseId('shinigami:0b9c1c4e-1f0a-4a3e-9d71-3f2a5b1c6d7e').provider, 'shinigami')
  })

  it('adapter shinigami tidak pernah keluar dari /v1/manga/detail/ & /v1/chapter/', async () => {
    const m = mockFetch({ api: () => ({ json: { retcode: 0, data: null } }) })
    try {
      for (const ref of ['..', '%2e%2e', 'a/../../x', 'a?b#c', '0b9c1c4e-1f0a-4a3e-9d71-3f2a5b1c6d7e']) {
        await createShinigami().detail(ref).catch(() => {})
        await createShinigami().chapter(ref).catch(() => {})
      }
    } finally {
      m.restore()
    }
    assert.ok(m.calls.length > 0)
    for (const url of m.calls) {
      const p = new URL(url).pathname
      assert.ok(/^\/v1\/(manga\/detail|chapter\/detail|chapter)\//.test(p), `path keluar jalur: ${url} -> ${p}`)
      assert.ok(!p.split('/').some(s => s === '..' || s === '.'), `dot-segment: ${p}`)
    }
  })

  it('adapter lumos tidak pernah keluar dari /comic/ & /read/', async () => {
    const m = mockFetch({ '': () => ({ status: 404, text: 'nf' }) })
    try {
      const l = createLumos()
      await l.detail('..').catch(() => {})
      await l.chapter('..:..').catch(() => {})
      await l.detail('solo-leveling').catch(() => {})
    } finally {
      m.restore()
    }
    assert.ok(m.calls.length > 0)
    for (const url of m.calls) {
      const p = new URL(url).pathname
      assert.ok(/^\/(comic|read)\//.test(p), `path keluar jalur: ${url} -> ${p}`)
    }
  })
})

describe('JWT', () => {
  const now = 1_700_000_000_000
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url')
  it('menolak alg none, tanda tangan kosong, payload dipalsukan, secret lain, sub bukan angka', () => {
    const real = signToken(5, 'rahasia', 60, now)
    const [h, b] = real.split('.')
    assert.equal(verifyToken(`${b64({ alg: 'none', typ: 'JWT' })}.${b64({ sub: 1, exp: 9e9 })}.`, 'rahasia', now), null)
    assert.equal(verifyToken(`${h}.${b64({ sub: 1, exp: 9e9 })}.${real.split('.')[2]}`, 'rahasia', now), null)
    assert.equal(verifyToken(`${h}.${b}.`, 'rahasia', now), null)
    assert.equal(verifyToken(real, 'x', now), null)
    assert.equal(verifyToken(signToken('1' as unknown as number, 'rahasia', 60, now), 'rahasia', now), null)
    assert.equal(verifyToken('', 'rahasia', now), null)
    assert.equal(verifyToken(signToken(5, '', 60, now), 'rahasia', now), null)
  })
})

describe('IP klien & header keamanan', () => {
  it('trustProxy default bukan "percaya semua hop" (X-Forwarded-For tidak bisa dipalsukan)', () => {
    assert.notEqual(config.trustProxy, true)
    assert.equal(parseTrustProxy(undefined), 1)
    assert.equal(parseTrustProxy(''), 1)
    assert.equal(parseTrustProxy('2'), 2)
    assert.equal(parseTrustProxy('false'), false)
    assert.deepEqual(parseTrustProxy('10.0.0.0/8, 172.16.0.0/12'), ['10.0.0.0/8', '172.16.0.0/12'])
  })

  it('applySecurityHeaders memasang nosniff, frame, referrer, hsts', () => {
    const h: Record<string, string> = {}
    applySecurityHeaders({ header: (k: string, v: string) => void (h[k.toLowerCase()] = v) })
    assert.equal(h['x-content-type-options'], 'nosniff')
    assert.equal(h['x-frame-options'], 'DENY')
    assert.equal(h['referrer-policy'], 'no-referrer')
    assert.match(h['strict-transport-security'], /max-age=\d+/)
  })
})

function fakeStore(): Store {
  const noop = async () => undefined
  return {
    syncProviders: noop, saveSummaries: noop, saveDetail: noop, addViews: noop, prune: noop,
    detailLocal: async () => null, findSeries: async () => null, siblings: async () => [], findChapter: async () => null, chapterLocal: async () => null, saveChapterImages: async () => {},
    searchLocal: async () => [], latestLocal: async () => [], popular: async () => [], recommended: async () => [], genres: async () => [], byGenre: async () => [],
    needingDetail: async () => [], ping: async () => true
  } as unknown as Store
}
const ttl = { fresh: 60_000, stale: 60_000 }
const cfg = { ttl: { search: ttl, latest: ttl, detail: ttl, chapter: ttl, db: ttl, genres: ttl }, aggregateTimeoutMs: 500, primaryTimeoutMs: 500 }

function countingAdapter(opts: { notFound?: boolean } = {}) {
  const calls = { detail: 0, chapter: 0 }
  const a: ProviderAdapter = {
    key: 'lumos', name: 'lumos', baseUrl: '',
    search: async () => [], latest: async () => [],
    detail: async ref => {
      calls.detail++
      if (opts.notFound) throw new NotFoundError('Not found upstream')
      return { id: `lumos:${ref}`, provider: 'lumos', title: ref, cover: '', url: '', type: null, status: null, genres: [], rating: null, chapterCount: null, latestChapter: null, sources: [{ provider: 'lumos', id: `lumos:${ref}` }], synopsis: '', author: '', artist: '', year: null, chapters: [] }
    },
    chapter: async ref => {
      calls.chapter++
      if (opts.notFound) throw new NotFoundError('Not found upstream')
      return { id: `lumos:${ref}`, seriesId: null, number: '1', images: ['a.jpg'], prev: null, next: null, imageHeaders: {} }
    },
    imageHeaders: () => ({})
  }
  return { a, calls }
}
const svcOf = (a: ProviderAdapter) => new ScraperService({ adapters: [a], cache: new Cache(new MemoryKV(), noopLog), store: fakeStore(), views: { hit() {} }, log: noopLog, cfg })

describe('amplifikasi ke upstream', () => {
  it('?refresh=1 dengan banyak id berbeda dibatasi anggaran global', async () => {
    const { a, calls } = countingAdapter()
    const svc = svcOf(a)
    for (let i = 0; i < 100; i++) await svc.detail(`lumos:s${i}`)
    const before = calls.detail
    for (let i = 0; i < 100; i++) await svc.detail(`lumos:s${i}`, true)
    const forced = calls.detail - before
    assert.ok(forced <= 30, `refresh paksa menembus upstream ${forced}x untuk 100 id`)
    for (let i = 0; i < 100; i++) await svc.chapter(`lumos:c${i}`)
    const beforeC = calls.chapter
    for (let i = 0; i < 100; i++) await svc.chapter(`lumos:c${i}`, true)
    assert.ok(calls.chapter - beforeC <= 30, `refresh chapter menembus upstream ${calls.chapter - beforeC}x`)
  })

  it('id yang tidak ada di-cache negatif: ulangan tidak lagi menembus upstream', async () => {
    const { a, calls } = countingAdapter({ notFound: true })
    const svc = svcOf(a)
    for (let i = 0; i < 20; i++) await assert.rejects(svc.detail('lumos:hantu'), { code: 'NOT_FOUND' })
    assert.equal(calls.detail, 1)
    for (let i = 0; i < 20; i++) await assert.rejects(svc.chapter('lumos:hantu-ch'), { code: 'NOT_FOUND' })
    assert.equal(calls.chapter, 1)
  })
})

describe('penyalahgunaan fitur sosial', () => {
  it('XP: referensi dengan provider fiktif ditolak (tidak bisa farming dengan ref karangan)', async () => {
    const queries: unknown[][] = []
    const xp = new XpService({ query: async (_s: string, p: unknown[]) => (queries.push(p), { rows: [{ id: 1 }] }) } as never)
    await assert.rejects(xp.report(1, 'read', 'zz-fake:123'), { code: 'BAD_REQUEST' })
    await assert.rejects(xp.report(1, 'bookmark', 'hantu:abc'), { code: 'BAD_REQUEST' })
    assert.equal(queries.length, 0)
    assert.equal(await xp.report(1, 'read', 'lumos:solo:ch-1'), 10)
    assert.equal(await xp.report(1, 'bookmark', 'shinigami:abc'), 3)
  })

  it('komentar: satu akun tidak bisa membanjiri (throttle per akun, bukan per IP)', async () => {
    let n = 0
    const times: number[] = []
    const pool = {
      async query(sql: string, p: unknown[] = []) {
        if (sql.includes("interval '60 seconds'")) return { rows: [{ n: times.filter(t => Date.now() - t < 60_000).length }] }
        if (sql.startsWith('INSERT INTO comments')) { times.push(Date.now()); return { rows: [{ id: ++n }] } }
        if (sql.includes('WHERE c.id = $1')) return { rows: [{ id: n, body: 'x', created_at: new Date(), uid: p[1], username: 'u', xp: 0, avatar_v: 0 }] }
        return { rows: [] }
      }
    }
    const svc = new CommentService(pool as never)
    let ok = 0
    let blocked = 0
    for (let i = 0; i < 40; i++) {
      try {
        await svc.add(1, 's:lumos:abc', `komentar ${i}`)
        ok++
      } catch (e) {
        assert.equal((e as { status?: number }).status, 429)
        blocked++
      }
    }
    assert.ok(ok <= 8, `lolos ${ok} komentar dalam sekejap`)
    assert.ok(blocked >= 30)
  })

  it('komentar: target dengan provider fiktif ditolak', async () => {
    const svc = new CommentService({ query: async () => ({ rows: [{ n: 0 }] }) } as never)
    await assert.rejects(svc.add(1, 's:zz-fake:abc', 'halo'), { code: 'BAD_REQUEST' })
    await assert.rejects(svc.list('c:hantu:abc'), { code: 'BAD_REQUEST' })
  })
})

describe('pencabutan token', () => {
  it('logout-all mematikan token lama, token versi baru berlaku; token lama tanpa tv tetap sah sampai dicabut', async () => {
    let tv = 0
    const pool = {
      async query(sql: string) {
        if (sql.startsWith('UPDATE users SET token_version')) { tv++; return { rows: [] } }
        if (sql.includes('FROM users WHERE id')) return { rows: [{ id: 1, username: 'rei', email: 'a@x.id', tokenVersion: tv }] }
        return { rows: [] }
      }
    }
    const cfg = { jwtSecret: 'rahasia-uji', googleClientId: 'x', ownerEmails: [], tokenTtlSec: 3600 } as never
    const svc = new AuthService(pool as never, cfg, noopLog)
    const legacy = `Bearer ${signToken(1, 'rahasia-uji', 3600).split('.').join('.')}`
    assert.equal((await svc.me(legacy)).id, 1)
    await svc.logoutAll(legacy)
    await assert.rejects(svc.me(legacy), { status: 401 })
    assert.equal((await svc.me(`Bearer ${signToken(1, 'rahasia-uji', 3600, Date.now(), 1)}`)).id, 1)
    await assert.rejects(svc.me(`Bearer ${signToken(1, 'rahasia-uji', 3600, Date.now(), 0)}`), { status: 401 })
  })
})

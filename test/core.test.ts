import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { CircuitBreaker, isoDate, memo, normTitle, parseId, slugify, splitOnce, withDeadline } from '../src/core/util.js'
import { Cache } from '../src/core/cache.js'
import { ViewCounter } from '../src/services/views.js'
import { ValidationError, dbErrorStatus } from '../src/core/errors.js'
import { MemoryKV, noopLog } from './helpers.js'

describe('ids & util', () => {
  it('parseId', () => {
    assert.deepEqual(parseId('lumos:12:34'), { provider: 'lumos', ref: '12:34' })
    for (const bad of ['x:1', 'lumos:', ':1', 'lumos', 'lumos:a/b']) assert.throws(() => parseId(bad), ValidationError)
  })
  it('normTitle & slugify', () => {
    assert.equal(normTitle('Solo-Leveling!'), normTitle('solo leveling'))
    assert.equal(normTitle('Café'), 'cafe')
    assert.equal(normTitle('To Tread into Darkness [Full ver.]'), normTitle('To Tread into Darkness'))
    assert.equal(normTitle('Solo Leveling (Full Version)'), 'sololeveling')
    assert.equal(slugify('Slice of Life'), 'slice-of-life')
  })
  it('isoDate', () => {
    assert.equal(isoDate('2024-01-02T03:04:05Z'), '2024-01-02T03:04:05.000Z')
    assert.equal(isoDate(1704164645), '2024-01-02T03:04:05.000Z')
    assert.equal(isoDate(1704164645000), '2024-01-02T03:04:05.000Z')
    assert.equal(isoDate('bukan tanggal'), null)
    assert.equal(isoDate(null), null)
  })
  it('splitOnce', () => {
    assert.deepEqual(splitOnce('a:b:c'), ['a', 'b:c'])
    assert.throws(() => splitOnce('abc'), ValidationError)
  })
  it('memo tidak menyimpan kegagalan', async () => {
    let n = 0
    const f = memo(async () => {
      if (++n === 1) throw new Error('x')
      return n
    }, 1000)
    await assert.rejects(f('a'))
    assert.equal(await f('a'), 2)
    assert.equal(await f('a'), 2)
  })
  it('withDeadline', async () => {
    await assert.rejects(withDeadline(new Promise(() => {}), 10), { code: 'UPSTREAM_TIMEOUT' })
    assert.equal(await withDeadline(Promise.resolve(1), 50), 1)
  })
})

describe('CircuitBreaker', () => {
  it('buka setelah threshold, half-open 1 probe setelah cooldown', async () => {
    const b = new CircuitBreaker(2, 20)
    assert.ok(b.canRequest())
    b.failure()
    b.failure()
    assert.equal(b.state, 'open')
    assert.equal(b.canRequest(), false)
    await new Promise(r => setTimeout(r, 25))
    assert.equal(b.state, 'half-open')
    assert.ok(b.canRequest())
    assert.equal(b.canRequest(), false, 'hanya 1 probe')
    b.success()
    assert.equal(b.state, 'closed')
  })
})

describe('Cache (SWR + singleflight)', () => {
  const ttl = { fresh: 30, stale: 5000 }

  it('miss -> hit, loader hanya sekali walau paralel', async () => {
    const c = new Cache(new MemoryKV(), noopLog)
    let n = 0
    const load = async () => (await new Promise(r => setTimeout(r, 10)), ++n)
    const [a, b] = await Promise.all([c.getOrSet('k', ttl, load), c.getOrSet('k', ttl, load)])
    assert.equal(a.value, 1)
    assert.equal(b.value, 1)
    assert.equal(n, 1)
    assert.equal((await c.getOrSet('k', ttl, load)).state, 'hit')
  })

  it('stale dilayani langsung, di-refresh di background; gagal -> tetap stale', async () => {
    const c = new Cache(new MemoryKV(), noopLog)
    await c.getOrSet('k', ttl, async () => 'v1')
    await new Promise(r => setTimeout(r, 40))
    const s = await c.getOrSet('k', ttl, async () => 'v2')
    assert.deepEqual(s, { value: 'v1', state: 'stale' })
    await new Promise(r => setTimeout(r, 10))
    assert.equal((await c.getOrSet('k', ttl, async () => 'x')).value, 'v2')

    await new Promise(r => setTimeout(r, 40))
    const failing = await c.getOrSet('k', ttl, async () => { throw new Error('down') })
    assert.deepEqual(failing, { value: 'v2', state: 'stale' })
  })

  it('miss + loader gagal -> error diteruskan', async () => {
    const c = new Cache(new MemoryKV(), noopLog)
    await assert.rejects(c.getOrSet('z', ttl, async () => { throw new Error('boom') }), /boom/)
  })

  it('Redis rusak -> cache dilewati, tidak melempar', async () => {
    const broken = { get: async () => { throw new Error('down') }, set: async () => { throw new Error('down') } }
    const c = new Cache(broken as any, noopLog)
    assert.deepEqual(await c.getOrSet('k', ttl, async () => 7), { value: 7, state: 'miss' })
  })
})

describe('Cache fallback (data lokal saat cache kosong)', () => {
  const ttl = { fresh: 30, stale: 5000 }
  it('miss + fallback -> dilayani fallback, loader jalan di background lalu cache terisi', async () => {
    const c = new Cache(new MemoryKV(), noopLog)
    const r = await c.getOrSet('k', ttl, async () => 'upstream', async () => 'lokal')
    assert.deepEqual(r, { value: 'lokal', state: 'fallback' })
    await new Promise(r => setTimeout(r, 10))
    assert.deepEqual(await c.getOrSet('k', ttl, async () => 'x', async () => 'lokal'), { value: 'upstream', state: 'hit' })
  })
  it('fallback null / error -> perilaku biasa (tunggu loader)', async () => {
    const c = new Cache(new MemoryKV(), noopLog)
    assert.deepEqual(await c.getOrSet('a', ttl, async () => 1, async () => null), { value: 1, state: 'miss' })
    assert.deepEqual(await c.getOrSet('b', ttl, async () => 2, async () => { throw new Error('db') }), { value: 2, state: 'miss' })
  })
  it('loader background gagal -> tidak melempar, fallback tetap dilayani', async () => {
    const c = new Cache(new MemoryKV(), noopLog)
    const r = await c.getOrSet('k', ttl, async () => { throw new Error('down') }, async () => 'lokal')
    assert.equal(r.value, 'lokal')
    await new Promise(r => setTimeout(r, 10))
    assert.equal((await c.getOrSet('k', ttl, async () => { throw new Error('down') }, async () => 'lokal')).value, 'lokal')
  })
})

describe('ViewCounter', () => {
  it('hit tidak hilang jika database gagal saat flush; terkirim di flush berikutnya', async () => {
    const sent: { provider: string; ref: string; n: number }[][] = []
    let fail = true
    const v = new ViewCounter({ addViews: async rows => { if (fail) throw new Error('db down'); sent.push(rows as any) } }, noopLog)
    v.hit('shinigami', '1'); v.hit('shinigami', '1'); v.hit('lumos', 'x')
    await v.flush()
    v.hit('shinigami', '1')
    fail = false
    await v.flush()
    assert.equal(sent.length, 1)
    assert.deepEqual(sent[0].map(r => `${r.provider}:${r.ref}:${r.n}`).sort(), ['lumos:x:1', 'shinigami:1:3'])
  })
})

describe('dbErrorStatus (error DB -> status HTTP)', () => {
  it('input buruk (22xxx) -> 400, bukan 500', () => {
    assert.equal(dbErrorStatus({ code: '22003', message: 'value out of range for type integer' })?.status, 400)
    assert.equal(dbErrorStatus({ code: '22P02' })?.status, 400)
  })
  it('bentrok / relasi -> 409', () => {
    assert.equal(dbErrorStatus({ code: '23505' })?.status, 409)
    assert.equal(dbErrorStatus({ code: '23503' })?.status, 409)
  })
  it('timeout query, koneksi habis/putus -> 503', () => {
    assert.equal(dbErrorStatus({ code: '57014' })?.status, 503)
    assert.equal(dbErrorStatus({ code: '53300' })?.status, 503)
    assert.equal(dbErrorStatus({ code: 'ECONNREFUSED' })?.status, 503)
    assert.equal(dbErrorStatus(new Error('timeout exceeded when trying to connect'))?.status, 503)
    assert.equal(dbErrorStatus(new Error('Connection terminated unexpectedly'))?.status, 503)
  })
  it('bug biasa (mis. NOT NULL, TypeError) tetap 500', () => {
    assert.equal(dbErrorStatus({ code: '23502' }), null)
    assert.equal(dbErrorStatus(new TypeError('x is undefined')), null)
    assert.equal(dbErrorStatus(null), null)
  })
})

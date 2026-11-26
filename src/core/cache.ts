import type { FastifyBaseLogger } from 'fastify'

/** Subset ioredis yang dipakai. */
export interface KV {
  get(key: string): Promise<string | null>
  set(key: string, value: string, mode: 'PX', ms: number): Promise<unknown>
}

/** fallback = cache kosong tapi ada data lokal (PostgreSQL) yang dilayani langsung sambil di-refresh di background */
export type CacheState = 'hit' | 'stale' | 'miss' | 'fallback'
interface Ttl {
  fresh: number
  stale: number
}
interface Envelope<T> {
  v: T
  f: number
}

/**
 * Cache Redis dengan stale-while-revalidate + singleflight.
 *  - fresh  : langsung dipakai
 *  - stale  : dipakai langsung, di-refresh di background
 *  - gagal  : jika loader error dan masih ada data stale, data stale dipakai
 *  - kosong : jika `fallback` (mis. baca dari PostgreSQL) menghasilkan data, itu dilayani langsung dan
 *             loader jalan di background -> tetap cepat setelah Redis di-restart / TTL habis
 * Redis mati -> cache dilewati (layanan tetap jalan).
 */
export class Cache {
  private readonly inflight = new Map<string, Promise<unknown>>()
  /** L1 di memori proses: jawaban fresh dilayani tanpa round-trip Redis & tanpa JSON.parse ulang. */
  private readonly l1 = new Map<string, Envelope<unknown>>()
  private static readonly L1_MAX = 400
  private static readonly L1_MAX_AGE_MS = 30_000

  constructor(
    private readonly kv: KV | null,
    private readonly log: FastifyBaseLogger
  ) {}

  async getOrSet<T>(
    key: string,
    ttl: Ttl,
    load: () => Promise<T>,
    fallback?: () => Promise<T | null>,
    force = false
  ): Promise<{ value: T; state: CacheState }> {
    if (force) return { value: await this.refresh(key, ttl, load), state: 'miss' }
    const mem = this.l1.get(key) as Envelope<T> | undefined
    if (mem && Date.now() < mem.f) return { value: mem.v, state: 'hit' }
    const env = await this.read<T>(key)
    if (env && Date.now() < env.f) {
      this.remember(key, env)
      return { value: env.v, state: 'hit' }
    }
    if (env) {
      void this.refresh(key, ttl, load).catch(() => {})
      return { value: env.v, state: 'stale' }
    }
    const local = fallback ? await fallback().catch(() => null) : null
    if (local) {
      void this.refresh(key, ttl, load).catch(() => {})
      return { value: local, state: 'fallback' }
    }
    return { value: await this.refresh(key, ttl, load), state: 'miss' }
  }

  async put<T>(key: string, v: T, ttl: Ttl): Promise<void> {
    await this.write(key, v, ttl)
  }

  private refresh<T>(key: string, ttl: Ttl, load: () => Promise<T>): Promise<T> {
    const running = this.inflight.get(key) as Promise<T> | undefined
    if (running) return running
    const p = (async () => {
      const v = await load()
      await this.write(key, v, ttl)
      return v
    })().finally(() => this.inflight.delete(key))
    this.inflight.set(key, p)
    return p
  }

  private async read<T>(key: string): Promise<Envelope<T> | null> {
    if (!this.kv) return null
    try {
      const raw = await this.kv.get(key)
      return raw ? (JSON.parse(raw) as Envelope<T>) : null
    } catch (e) {
      this.log.warn({ err: (e as Error).message, key }, 'cache read failed')
      return null
    }
  }

  /** Simpan ke L1; kedaluwarsa L1 dibatasi supaya data tidak menua jauh dari Redis. */
  private remember<T>(key: string, env: Envelope<T>) {
    const f = Math.min(env.f, Date.now() + Cache.L1_MAX_AGE_MS)
    this.l1.delete(key)
    this.l1.set(key, { v: env.v, f })
    if (this.l1.size > Cache.L1_MAX) this.l1.delete(this.l1.keys().next().value as string)
  }

  private async write<T>(key: string, v: T, ttl: Ttl): Promise<void> {
    const env = { v, f: Date.now() + ttl.fresh } satisfies Envelope<T>
    this.remember(key, env)
    if (!this.kv) return
    try {
      await this.kv.set(key, JSON.stringify(env), 'PX', ttl.fresh + ttl.stale)
    } catch (e) {
      this.log.warn({ err: (e as Error).message, key }, 'cache write failed')
    }
  }
}

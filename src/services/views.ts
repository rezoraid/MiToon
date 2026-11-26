import type { FastifyBaseLogger } from 'fastify'
import type { ProviderKey, Store } from '../core/types.js'

/** Buffer hit detail di memori, ditulis ke PostgreSQL berkala dalam 1 query (tidak membebani request). */
export class ViewCounter {
  private readonly buf = new Map<string, { provider: ProviderKey; ref: string; n: number }>()
  private timer?: NodeJS.Timeout

  constructor(
    private readonly store: Pick<Store, 'addViews'>,
    private readonly log: FastifyBaseLogger,
    private readonly intervalMs = 15_000,
    private readonly immediate = false
  ) {}

  hit(provider: ProviderKey, ref: string) {
    const key = `${provider}\0${ref}`
    const e = this.buf.get(key)
    if (e) e.n++
    else this.buf.set(key, { provider, ref, n: 1 })
    if (this.immediate) void this.flush()
  }

  start() {
    this.timer = setInterval(() => void this.flush(), this.intervalMs)
    this.timer.unref()
  }

  async flush() {
    if (!this.buf.size) return
    const rows = [...this.buf.values()]
    this.buf.clear()
    try {
      await this.store.addViews(rows)
    } catch (e) {
      this.log.warn({ err: (e as Error).message }, 'flush views failed')
      // Kembalikan ke buffer supaya hit tidak hilang saat database sempat bermasalah (dibatasi agar memori aman).
      for (const r of rows) {
        const key = `${r.provider}\0${r.ref}`
        const cur = this.buf.get(key)
        if (cur) cur.n += r.n
        else if (this.buf.size < 5000) this.buf.set(key, r)
      }
    }
  }

  async stop() {
    clearInterval(this.timer)
    await this.flush()
  }
}

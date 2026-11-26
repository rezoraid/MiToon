import type { FastifyBaseLogger } from 'fastify'
import { parseId } from '../core/util.js'
import type { ProviderKey, Store } from '../core/types.js'
import type { ScraperService } from './scraper.js'

/**
 * Tugas latar berkala:
 *  1. ambil "latest" tiap provider -> cache hangat + tabel series/genres terisi
 *  2. perkaya beberapa seri tanpa genre (mis. Lumos, yang list-nya tidak memuat genre) lewat halaman detail
 *  3. bersihkan data view lama
 */
export class Warmer {
  private timer?: NodeJS.Timeout
  private crawlTimer?: NodeJS.Timeout
  private stopped = false
  private runs = 0
  private readonly seen = new Map<string, string | null>()

  constructor(
    private readonly svc: ScraperService,
    private readonly store: Store,
    private readonly providers: ProviderKey[],
    private readonly log: FastifyBaseLogger,
    private readonly cfg: {
      intervalMs: number
      enrich: number
      maintainEvery?: number
      /** Seri yang `latestChapter`-nya berubah di daftar latest langsung diambil ulang (memicu push chapter baru). */
      watchNew?: boolean
      crawl?: { pages: number; size: number; firstDelayMs: number; everyMs: number; enrich: number; seeds: string[] }
    }
  ) {}

  start(firstDelayMs = 5_000) {
    this.schedule(firstDelayMs)
    if (this.cfg.crawl) this.scheduleCrawl(this.cfg.crawl.firstDelayMs)
  }

  stop() {
    this.stopped = true
    clearTimeout(this.timer)
    clearTimeout(this.crawlTimer)
  }

  private schedule(ms: number) {
    if (this.stopped) return
    this.timer = setTimeout(() => void this.run().catch(e => this.log.warn({ err: (e as Error)?.message }, 'warm run failed')).finally(() => this.schedule(this.cfg.intervalMs)), ms)
    this.timer.unref()
  }

  private scheduleCrawl(ms: number) {
    if (this.stopped || !this.cfg.crawl) return
    this.crawlTimer = setTimeout(() => void this.crawl().catch(e => this.log.warn({ err: (e as Error)?.message }, 'crawl failed')).finally(() => this.scheduleCrawl(this.cfg.crawl!.everyMs)), ms)
    this.crawlTimer.unref()
  }

  /**
   * Isi DB dalam-dalam supaya filter genre berisi banyak seri dari semua sumber:
   *  - tiap provider: halaman latest 1..N (berhenti saat kosong) -> semua seri + genre dari list tersimpan
   *  - seed pencarian per nama genre (provider tanpa halaman genre native) -> seri ber-tag genre itu
   *  - Lumos: browse native per genre dasar, beberapa halaman
   *  - perkaya detail seri yang belum punya genre (Shinigami/Lumos list tanpa genre)
   * Pelan & berurutan (jeda antar request) supaya upstream tidak terbebani.
   */
  async crawl() {
    const c = this.cfg.crawl
    if (!c) return
    const pause = () => new Promise(r => setTimeout(r, 300))
    const count = (r: unknown) => (r as { data?: unknown[] } | null)?.data?.length ?? 0
    let saved = 0
    for (const provider of this.providers) {
      for (let page = 1; page <= c.pages && !this.stopped; page++) {
        const r = await this.svc.latest({ page, limit: c.size, provider }).catch(() => null)
        const n = count(r)
        saved += n
        if (n === 0) break
        await pause()
      }
      for (const q of c.seeds) {
        if (this.stopped) return
        saved += count(await this.svc.search(q, { page: 1, limit: c.size, provider }).catch(() => null))
        await pause()
      }
    }
    for (const slug of c.seeds.map(s => s.replace(/[^a-z0-9]+/g, '-'))) {
      for (let page = 1; page <= 3 && !this.stopped; page++) {
        const r = await this.svc.byGenre(slug, { page, limit: c.size }).catch(() => null)
        if (count(r) === 0) break
        await pause()
      }
    }
    // Detail untuk seri tanpa genre: 4 worker paralel.
    try {
      const queue = await this.store.needingDetail(c.enrich)
      await Promise.all(
        Array.from({ length: 4 }, async () => {
          for (let it = queue.shift(); it && !this.stopped; it = queue.shift()) {
            await this.svc.refreshDetail(it.provider, it.ref).catch(() => undefined)
          }
        })
      )
    } catch (e) {
      this.log.warn({ err: (e as Error).message }, 'crawl enrich failed')
    }
    this.log.info({ saved }, 'catalog crawl done')
  }

  /** Daftar yang diminta app saat dibuka (limit 12 & 6 = persis query Home/Explore) dipanaskan terus -> request pertama user selalu kena cache. */
  private async warmLists() {
    const jobs: Array<[string, () => Promise<unknown>]> = [
      ['latest', () => this.svc.latest({ page: 1, limit: 12 })],
      ['latest-p2', () => this.svc.latest({ page: 2, limit: 12 })],
      ['popular', () => this.svc.popular({ page: 1, limit: 12, days: 7 })],
      ['popular-spotlight', () => this.svc.popular({ page: 1, limit: 6, days: 7 })],
      ['genres', () => this.svc.genres()]
    ]
    const results = await Promise.all(jobs.map(([name, fn]) => fn().catch(e => (this.log.warn({ name, err: (e as Error).message }, 'warm list failed'), null))))
    // Detail seri teratas ikut dipanaskan (refreshDetail tidak menghitung hit) -> app yang prefetch detail dilayani dari cache.
    const ids = new Set<string>()
    for (const r of results.slice(0, 4) as Array<{ data?: Array<{ id: string }> } | null>) r?.data?.slice(0, 8).forEach(s => ids.add(s.id))
    const queue = [...ids]
    await Promise.all(
      Array.from({ length: 4 }, async () => {
        for (let id = queue.shift(); id && !this.stopped; id = queue.shift()) {
          try {
            const { provider, ref } = parseId(id)
            await this.svc.refreshDetail(provider, ref)
          } catch {
            /* detail gagal tidak menghentikan warm */
          }
        }
      })
    )
  }

  /** Bandingkan `latestChapter` dengan putaran sebelumnya; yang berubah diambil ulang (force) supaya chapter baru tersimpan & ter-push. Putaran pertama hanya mencatat. */
  private async watch(items?: Array<{ id: string; latestChapter: string | null }>) {
    for (const s of items ?? []) {
      if (this.stopped) return
      const prev = this.seen.get(s.id)
      this.seen.set(s.id, s.latestChapter)
      if (prev === undefined || prev === s.latestChapter) continue
      try {
        const { provider, ref } = parseId(s.id)
        await this.svc.refreshDetail(provider, ref, true)
      } catch {
        /* gagal = dicoba lagi di perubahan berikutnya */
      }
    }
    if (this.seen.size > 2000) this.seen.clear()
  }

  /** Genre dasar halaman 1 (query yang sama dengan pill Home) — dijalankan tiap putaran agar pill Home selalu cache-hit. */
  private async warmGenres() {
    for (const slug of ['action', 'romance', 'fantasy', 'comedy', 'drama', 'isekai', 'adventure', 'horror']) {
      if (this.stopped) return
      await this.svc.byGenre(slug, { page: 1, limit: 12 }).catch(() => undefined)
    }
  }

  async run() {
    this.runs++
    await this.warmLists()
    // Genre pill Home: tiap 2 putaran supaya cache tetap panas tanpa membanjiri upstream.
    if ((this.runs - 1) % 2 === 0) await this.warmGenres()
    // Pemeliharaan (enrich detail + prune) cukup tiap beberapa putaran.
    if ((this.runs - 1) % (this.cfg.maintainEvery ?? 1) !== 0) return
    for (const provider of this.providers) {
      const r = await this.svc.latest({ page: 1, limit: 20, provider }).catch(e => (this.log.warn({ provider, err: (e as Error).message }, 'warm latest failed'), null))
      if (this.cfg.watchNew) await this.watch(r?.data)
    }
    try {
      for (const { provider, ref } of await this.store.needingDetail(this.cfg.enrich)) {
        if (this.stopped) return
        await this.svc.refreshDetail(provider, ref).catch(e => this.log.warn({ provider, ref, err: (e as Error).message }, 'warm detail failed'))
      }
      await this.store.prune()
    } catch (e) {
      this.log.warn({ err: (e as Error).message }, 'warm maintenance failed')
    }
  }
}

import type { FastifyBaseLogger } from 'fastify'
import type { Config } from '../config.js'
import { CircuitBreaker, isVariantTitle, normTitle, parseId, slugify, withDeadline } from '../core/util.js'
import type { Cache, CacheState } from '../core/cache.js'
import { NotFoundError, TimeoutError, UnavailableError, UpstreamError, ValidationError, normalizeError } from '../core/errors.js'
import type {
  ChapterContent,
  ChapterSummary,
  DataSource,
  DbPage,
  GenreFilters,
  GenreRow,
  ProviderAdapter,
  ProviderKey,
  ProviderStatus,
  SeriesDetail,
  SeriesSummary,
  Store
} from '../core/types.js'

interface ViewSink {
  hit(provider: ProviderKey, ref: string): void
}

interface ListMeta {
  page: number
  limit: number
  count: number
  providers: Record<string, ProviderStatus>
  degraded: boolean
  source: DataSource
  /** Pesan singkat provider yang gagal (untuk diagnosa), mis. `lumos: HTTP 403 [server: cloudflare]`. */
  errors?: Record<string, string>
}
interface ListResult {
  data: SeriesSummary[]
  meta: ListMeta
}
interface ItemMeta {
  provider: ProviderKey
  requestedId: string
  fallback: boolean
  source: DataSource
}
interface PageQuery {
  page: number
  limit: number
  provider?: ProviderKey
}

type ServiceConfig = Pick<Config, 'ttl' | 'aggregateTimeoutMs' | 'primaryTimeoutMs'> & {
  serverless?: boolean
  listGraceMs?: number
  prefetchNextChapter?: boolean
}
const offsetOf = (q: PageQuery) => (q.page - 1) * q.limit
const dbPage = (q: PageQuery): DbPage => ({ limit: q.limit, offset: offsetOf(q), provider: q.provider })
const sameNumber = (a: string, b: string) => (Number.isFinite(+a) && Number.isFinite(+b) ? +a === +b : a === b)
const stateToSource = (s: CacheState): DataSource => (s === 'hit' ? 'cache' : s === 'stale' ? 'stale' : s === 'fallback' ? 'db' : 'upstream')

function statusOf(e: unknown): ProviderStatus {
  if (e instanceof TimeoutError) return 'timeout'
  if (e instanceof UnavailableError) return 'unavailable'
  return 'error'
}

/** Sisakan item bertipe `type` (mis. "manga"); tanpa `type` semua lolos. */
function filterByType(items: SeriesSummary[], type?: string): SeriesSummary[] {
  return type ? items.filter(i => i.type === type) : items
}

/** Gabungkan hasil beberapa provider: round-robin, deduplikasi judul sama, kumpulkan `sources`. */
function mergeSeries(lists: SeriesSummary[][]): SeriesSummary[] {
  const byTitle = new Map<string, SeriesSummary>()
  const out: SeriesSummary[] = []
  const longest = Math.max(0, ...lists.map(l => l.length))
  for (let i = 0; i < longest; i++) {
    for (const list of lists) {
      const item = list[i]
      if (!item) continue
      const key = normTitle(item.title)
      const first = key ? byTitle.get(key) : undefined
      if (!first) {
        const copy = { ...item, genres: [...item.genres], sources: [...item.sources] }
        if (key) byTitle.set(key, copy)
        out.push(copy)
        continue
      }
      // Varian "[Full ver.]" ketemu lebih dulu -> yang berjudul bersih jadi wakil kartu (sumber varian tetap disimpan).
      if (isVariantTitle(first.title) && !isVariantTitle(item.title)) {
        const old = { ...first }
        Object.assign(first, { ...item, genres: [...item.genres], sources: [...item.sources], cover: item.cover || old.cover })
        first.sources.push(...old.sources.filter(s => !first.sources.some(x => x.id === s.id)))
        first.genres = [...new Set([...first.genres, ...old.genres])]
        first.rating ??= old.rating
        continue
      }
      first.sources.push(...item.sources.filter(s => !first.sources.some(x => x.id === s.id)))
      first.genres = [...new Set([...first.genres, ...item.genres])]
      first.cover ||= item.cover
      first.rating ??= item.rating
      first.type ??= item.type
      first.status ??= item.status
    }
  }
  return out
}

/**
 * Tunggu semua task, KECUALI: begitu satu task "bagus" (`good`) selesai, sisanya hanya ditunggu `graceMs` lagi.
 * Task yang belum selesai diganti `timedOut(i)`; task aslinya tetap jalan (mengisi cache untuk request berikutnya).
 * graceMs <= 0 = tunggu semua. Task tidak boleh reject (tangkap error di dalamnya).
 */
export function settleWithGrace<T>(tasks: Promise<T>[], graceMs: number, good: (v: T) => boolean, timedOut: (i: number) => T): Promise<T[]> {
  return new Promise(resolve => {
    const out: (T | undefined)[] = new Array(tasks.length).fill(undefined)
    const have: boolean[] = new Array(tasks.length).fill(false)
    let left = tasks.length
    let timer: NodeJS.Timeout | undefined
    let done = false
    const finish = () => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve(out.map((v, i) => (have[i] ? (v as T) : timedOut(i))))
    }
    if (!left) return finish()
    tasks.forEach((t, i) =>
      t.then(v => {
        out[i] = v
        have[i] = true
        if (--left === 0) return finish()
        if (graceMs > 0 && !timer && good(v)) timer = setTimeout(finish, graceMs)
      })
    )
  })
}

export class ScraperService {
  private readonly adapters = new Map<ProviderKey, ProviderAdapter>()
  private readonly breakers = new Map<ProviderKey, CircuitBreaker>()
  private readonly stats = new Map<ProviderKey, { lastOkAt: string | null; lastError: string | null; lastMs: number | null }>()

  constructor(
    private readonly o: {
      adapters: ProviderAdapter[] // urutan = prioritas
      cache: Cache
      store: Store
      views: ViewSink
      log: FastifyBaseLogger
      cfg: ServiceConfig
    }
  ) {
    for (const a of o.adapters) {
      this.adapters.set(a.key, a)
      this.breakers.set(a.key, new CircuitBreaker())
      this.stats.set(a.key, { lastOkAt: null, lastError: null, lastMs: null })
    }
  }

  // ------------------------------------------------------------------ infra

  private adapter(key: ProviderKey): ProviderAdapter {
    const a = this.adapters.get(key)
    if (!a) throw new ValidationError(`Provider "${key}" tidak aktif`)
    return a
  }

  private pick(provider?: ProviderKey): ProviderAdapter[] {
    return provider ? [this.adapter(provider)] : [...this.adapters.values()]
  }

  /** Semua panggilan ke upstream lewat sini: circuit breaker + statistik + normalisasi error. */
  private async call<T>(p: ProviderAdapter, fn: () => Promise<T>): Promise<T> {
    const br = this.breakers.get(p.key)!
    if (!br.canRequest()) throw new UnavailableError(`Provider ${p.key} sedang tidak tersedia (circuit open)`)
    const st = this.stats.get(p.key)!
    const t0 = performance.now()
    try {
      const v = await fn()
      br.success()
      st.lastOkAt = new Date().toISOString()
      st.lastMs = Math.round(performance.now() - t0)
      return v
    } catch (e) {
      if (e instanceof NotFoundError) {
        br.success() // upstream sehat, datanya saja tidak ada
        throw e
      }
      br.failure()
      const err = normalizeError(e)
      st.lastError = err.message
      this.o.log.warn({ provider: p.key, err: err.message }, 'upstream call failed')
      throw err
    }
  }

  /** Persistensi: background (server biasa) atau ditunggu inline (serverless, karena proses dibekukan setelah respons). */
  private async background(p: Promise<unknown>, what: string) {
    const safe = p.catch(e => this.o.log.warn({ err: (e as Error).message }, `background ${what} failed`))
    if (this.o.cfg.serverless) await safe
  }

  private readonly coverJobs = new Set<string>()

  /**
   * Seri di daftar yang covernya kosong: ambil halaman detailnya di background (maks. 3 paralel) supaya cover tersimpan di DB
   * dan request daftar berikutnya sudah membawanya. Tidak di serverless (proses dibekukan setelah respons).
   */
  private fillCovers(items: SeriesSummary[]) {
    if (this.o.cfg.serverless) return
    const todo = items.filter(i => i.id && !/^https?:\/\//.test(i.cover || '') && !this.coverJobs.has(i.id)).slice(0, 9)
    if (!todo.length) return
    todo.forEach(i => this.coverJobs.add(i.id))
    const queue = [...todo]
    void Promise.all(
      Array.from({ length: Math.min(3, queue.length) }, async () => {
        for (let it = queue.shift(); it; it = queue.shift()) {
          try {
            const { provider, ref } = parseId(it.id)
            await this.refreshDetail(provider, ref)
          } catch {
            /* gagal -> dicoba lagi oleh warmer (needingDetail) */
          } finally {
            setTimeout(() => this.coverJobs.delete(it.id), 10 * 60_000).unref()
          }
        }
      })
    )
  }

  providers() {
    return [...this.adapters.values()].map((a, i) => ({
      key: a.key,
      name: a.name,
      baseUrl: a.baseUrl,
      priority: i,
      circuit: this.breakers.get(a.key)!.state,
      ...this.stats.get(a.key)!,
      imageHeaders: a.imageHeaders()
    }))
  }

  // ------------------------------------------------------------------ list (search / latest)

  private listFrom(p: ProviderAdapter, kind: 'search' | 'latest', a: { q?: string; page: number; limit: number; type?: string }) {
    // Cache kosong (Redis restart / TTL habis) -> tampilkan data tersimpan di PostgreSQL seketika, segarkan di belakang.
    // Tidak untuk serverless (proses dibeku setelah respons, refresh latar tidak bisa diandalkan) dan tidak untuk search.
    const local =
      kind === 'latest' && !this.o.cfg.serverless
        ? async () => {
            const rows = await this.o.store.latestLocal({ limit: a.limit, offset: (a.page - 1) * a.limit, provider: p.key })
            return rows.length >= Math.min(a.limit, 6) ? rows : null
          }
        : undefined
    const key =
      kind === 'search'
        ? `s:${p.key}:${a.q!.trim().toLowerCase()}:${a.page}:${a.limit}`
        : `l:${p.key}:${a.type ?? ''}:${a.page}:${a.limit}`
    return this.o.cache.getOrSet(key, this.o.cfg.ttl[kind], async () => {
      const items = await this.call(p, () => (kind === 'search' ? p.search(a.q!, a) : p.latest(a)))
      await this.background(this.o.store.saveSummaries(items), 'saveSummaries')
      return items
    }, local)
  }

  private async fanOut(
    q: PageQuery,
    run: (p: ProviderAdapter) => Promise<{ value: SeriesSummary[]; state: CacheState }>,
    local: () => Promise<SeriesSummary[]>
  ): Promise<ListResult> {
    type Outcome = { key: ProviderKey; items: SeriesSummary[]; state: CacheState | null; status: ProviderStatus; error?: string }
    const pending = this.pick(q.provider)
    const settled = await settleWithGrace<Outcome>(
      pending.map(async (p): Promise<Outcome> => {
        try {
          const r = await withDeadline(run(p), this.o.cfg.aggregateTimeoutMs)
          return { key: p.key, items: r.value, state: r.state, status: r.state === 'stale' ? 'stale' : 'ok' }
        } catch (e) {
          return { key: p.key, items: [], state: null, status: statusOf(e), error: (e as Error)?.message }
        }
      }),
      this.o.cfg.listGraceMs ?? 0,
      o => o.state !== null && o.items.length > 0,
      i => ({ key: pending[i].key, items: [], state: null, status: 'timeout' })
    )
    const providers = Object.fromEntries(settled.map(r => [r.key, r.status]))
    const errors = Object.fromEntries(settled.flatMap(r => ('error' in r && r.error ? [[r.key, String(r.error).slice(0, 160)]] : [])))
    const alive = settled.filter(r => r.state)

    if (!alive.length) {
      // Semua provider gagal -> data terakhir yang pernah tersimpan di PostgreSQL.
      const rows = await local().catch(() => [])
      if (!rows.length) {
        const why = settled.map(r => ('error' in r && r.error ? `${r.key}: ${r.error}` : '')).filter(Boolean).join(' | ')
        throw new UpstreamError(`Semua provider gagal dan belum ada data tersimpan${why ? ` (${why})` : ''}`)
      }
      return { data: rows, meta: { page: q.page, limit: q.limit, count: rows.length, providers, degraded: true, source: 'db', ...(Object.keys(errors).length ? { errors } : {}) } }
    }

    const data = mergeSeries(alive.map(r => r.items))
    this.fillCovers(data)
    const source: DataSource = alive.some(r => r.state === 'miss') ? 'upstream' : alive.some(r => r.state === 'stale') ? 'stale' : 'cache'
    return {
      data,
      meta: { page: q.page, limit: q.limit, count: data.length, providers, degraded: alive.length < settled.length, source, ...(Object.keys(errors).length ? { errors } : {}) }
    }
  }

  search(q: string, o: PageQuery): Promise<ListResult> {
    return this.fanOut(o, p => this.listFrom(p, 'search', { q, ...o }), () => this.o.store.searchLocal(q, dbPage(o)))
  }

  latest(o: PageQuery & { type?: string }): Promise<ListResult> {
    return this.fanOut(
      o,
      async p => {
        // `type` tidak dikirim ke upstream (cache key tetap satu per halaman), difilter setelahnya.
        const r = await this.listFrom(p, 'latest', { ...o, type: undefined })
        return { ...r, value: filterByType(r.value, o.type) }
      },
      () => this.o.store.latestLocal(dbPage(o))
    )
  }

  // ------------------------------------------------------------------ DB-backed lists (popular / genre)

  private async dbList(key: string, q: PageQuery, load: () => Promise<SeriesSummary[]>): Promise<ListResult> {
    const { value, state } = await this.o.cache.getOrSet(key, this.o.cfg.ttl.db, load)
    this.fillCovers(value)
    return {
      data: value,
      meta: { page: q.page, limit: q.limit, count: value.length, providers: {}, degraded: false, source: state === 'hit' ? 'cache' : state === 'stale' ? 'stale' : 'db' }
    }
  }

  /** Populer = paling banyak dibuka lewat API ini (hit detail) dalam `days` hari terakhir. */
  popular(o: PageQuery & { days: number }): Promise<ListResult> {
    return this.dbList(`p:${o.provider ?? ''}:${o.days}:${o.page}:${o.limit}`, o, () => this.o.store.popular({ ...dbPage(o), days: o.days }))
  }

  /** Rekomendasi = rating tertinggi dari data tersimpan. */
  recommended(o: PageQuery): Promise<ListResult> {
    return this.dbList(`r2:${o.provider ?? ''}:${o.page}:${o.limit}`, o, () => this.o.store.recommended(dbPage(o)))
  }

  async genres(): Promise<{ data: GenreRow[]; source: DataSource }> {
    const { value, state } = await this.o.cache.getOrSet('g:all', this.o.cfg.ttl.genres, async () => {
      const rows = await this.o.store.genres()
      const seen = new Set(rows.map(r => r.slug))
      // Genre yang dikenal provider tapi belum pernah muncul di DB (mis. Lumos) ikut ditampilkan.
      for (const a of this.adapters.values()) {
        const names = a.listGenres ? await withDeadline(a.listGenres(), 3000).catch(() => []) : []
        for (const name of names) {
          const slug = slugify(name)
          if (slug && !seen.has(slug)) {
            seen.add(slug)
            rows.push({ slug, name, count: 0 })
          }
        }
      }
      return rows
    })
    return { data: value, source: state === 'hit' ? 'cache' : state === 'stale' ? 'stale' : 'db' }
  }

  /** Provider dengan katalog native (Lumos) ditarik live dulu -> tersimpan ke DB lengkap dengan genre. */
  private browseFrom(p: ProviderAdapter, slug: string, o: PageQuery & GenreFilters) {
    const key = `b:${p.key}:${slug}:${o.sort}:${o.type ?? ''}:${o.status ?? ''}:${o.page}`
    return this.o.cache.getOrSet(key, this.o.cfg.ttl.latest, async () => {
      const items = await this.call(p, () => p.browse!({ genre: slug, page: o.page, sort: o.sort ?? 'latest', type: o.type, status: o.status }))
      await this.o.store.saveSummaries(items).catch(e => this.o.log.warn({ err: (e as Error).message }, 'save browse failed'))
      return items.length
    })
  }

  async byGenre(slug: string, o: PageQuery & GenreFilters): Promise<ListResult> {
    const providers: Record<string, ProviderStatus> = {}
    const key = `gs:${slug}:${o.provider ?? ''}:${o.sort ?? 'latest'}:${o.type ?? ''}:${o.status ?? ''}:${o.page}:${o.limit}`
    const native = this.pick(o.provider).filter(p => p.browse)
    const sync = async (budgetMs: number) => {
      await Promise.all(
        native.map(async p => {
          try {
            const r = await withDeadline(this.browseFrom(p, slug, o), budgetMs)
            providers[p.key] = r.state === 'stale' ? 'stale' : 'ok'
          } catch (e) {
            providers[p.key] = statusOf(e)
          }
        })
      )
    }
    const load = () => this.o.store.byGenre(slug, { ...dbPage(o), sort: o.sort, type: o.type, status: o.status })
    const read = () => this.dbList(key, o, load)

    // Katalog native (Lumos) ditarik live ke DB. Dulu SELALU ditunggu (sampai 5 dtk tiap kombinasi filter baru);
    // sekarang data tersimpan dilayani langsung dan sinkronisasi jalan di belakang. Hanya bila DB masih kosong untuk
    // genre ini, sinkronisasi ditunggu. Serverless membekukan proses setelah respons -> tunggu singkat saja.
    let res = await read()
    if (!native.length) {
      /* tidak ada provider native */
    } else if (res.data.length === 0) {
      await sync(this.o.cfg.aggregateTimeoutMs)
      const data = await load() // langsung ke DB: hasil kosong sebelumnya masih tersimpan di cache
      res = { data, meta: { ...res.meta, count: data.length, source: 'db' } }
    } else if (this.o.cfg.serverless) {
      await sync(1500)
    } else {
      void sync(this.o.cfg.aggregateTimeoutMs)
      for (const p of native) providers[p.key] = 'stale'
    }
    res.meta.providers = providers
    res.meta.degraded = Object.values(providers).some(s => s !== 'ok' && s !== 'stale')
    return res
  }

  // ------------------------------------------------------------------ detail & chapter (dengan fallback)

  private readonly forcedAt = new Map<string, number>()
  private forceLog: number[] = []
  /** Anggaran global ambil-ulang paksa per menit (semua pengguna): ?refresh=1 tidak bisa dipakai membanjiri upstream dengan id berbeda-beda. */
  private static readonly FORCE_GLOBAL_PER_MIN = 30
  /** Ambil ulang manual dari pengguna: dibatasi 1x per kunci per menit + anggaran global per menit di server (di luar batas harian di aplikasi). */
  private allowForce(key: string): boolean {
    const now = Date.now()
    if (now - (this.forcedAt.get(key) ?? 0) < 60_000) return false
    this.forceLog = this.forceLog.filter(t => now - t < 60_000)
    if (this.forceLog.length >= ScraperService.FORCE_GLOBAL_PER_MIN) return false
    this.forceLog.push(now)
    this.forcedAt.set(key, now)
    if (this.forcedAt.size > 500) for (const [k, t] of this.forcedAt) if (now - t >= 60_000) this.forcedAt.delete(k)
    return true
  }

  /** Cache negatif: id yang baru saja 404 di upstream tidak ditanyakan lagi ke upstream selama 60 detik (anti-banjir id acak). */
  private readonly missing = new Map<string, number>()
  private static readonly MISSING_TTL_MS = 60_000
  private static readonly MISSING_MAX = 2000
  private async unlessMissing<T>(key: string, force: boolean, run: () => Promise<T>): Promise<T> {
    const now = Date.now()
    if (!force && (this.missing.get(key) ?? 0) > now) throw new NotFoundError('Not found upstream')
    try {
      const v = await run()
      this.missing.delete(key)
      return v
    } catch (e) {
      if (e instanceof NotFoundError) {
        this.missing.set(key, now + ScraperService.MISSING_TTL_MS)
        if (this.missing.size > ScraperService.MISSING_MAX) for (const [k, t] of this.missing) if (t <= now || this.missing.size > ScraperService.MISSING_MAX) this.missing.delete(k)
      }
      throw e
    }
  }

  private detailFrom(p: ProviderAdapter, ref: string, force = false) {
    return this.unlessMissing(`d:${p.key}:${ref}`, force, () => this.detailCached(p, ref, force))
  }

  private detailCached(p: ProviderAdapter, ref: string, force: boolean) {
    return this.o.cache.getOrSet<SeriesDetail>(
      `d2:${p.key}:${ref}`,
      this.o.cfg.ttl.detail,
      async () => {
        const d = await this.call(p, () => p.detail(ref))
        await this.background(this.o.store.saveDetail(d), 'saveDetail')
        return d
      },
      // Cache kosong (Redis restart / TTL habis) tapi seri pernah diambil -> langsung dari PostgreSQL, refresh di background.
      // Di serverless proses dibekukan setelah respons, jadi refresh background tidak bisa diandalkan -> tanpa fallback.
      this.o.cfg.serverless ? undefined : () => this.o.store.detailLocal(p.key, ref),
      force
    )
  }

  private chapterFrom(p: ProviderAdapter, ref: string, force = false) {
    return this.unlessMissing(`c:${p.key}:${ref}`, force, () => this.chapterCached(p, ref, force))
  }

  private chapterCached(p: ProviderAdapter, ref: string, force: boolean) {
    return this.o.cache.getOrSet<ChapterContent>(
      `c:${p.key}:${ref}`,
      this.o.cfg.ttl.chapter,
      () => this.call(p, () => p.chapter(ref)),
      this.o.cfg.serverless ? undefined : () => this.o.store.chapterLocal(p.key, ref),
      force
    )
  }

  /** Ambil chapter berikutnya di background supaya "lanjut baca" langsung dari cache. Kegagalan diabaikan. */
  private prefetchChapter(nextId: string | null) {
    if (!nextId || this.o.cfg.serverless || this.o.cfg.prefetchNextChapter === false) return
    try {
      const { provider, ref } = parseId(nextId)
      const a = this.adapters.get(provider)
      if (a && this.breakers.get(provider)!.available) void this.chapterFrom(a, ref).catch(() => {})
    } catch {}
  }

  /** Dipakai warmer: isi cache + DB tanpa menghitung view dan tanpa fallback. */
  async refreshDetail(provider: ProviderKey, ref: string, force = false): Promise<void> {
    await this.detailFrom(this.adapter(provider), ref, force)
  }

  /** Seri yang sama di provider lain: dari DB (norm_title), jika kosong cari lewat search provider lain. */
  private async siblingsOf(provider: ProviderKey, ref: string): Promise<{ provider: ProviderKey; ref: string }[]> {
    const found = await this.o.store.findSeries(provider, ref).catch(() => null)
    if (!found) return []
    const out: { provider: ProviderKey; ref: string }[] = await this.o.store.siblings(found.normTitle, provider).catch(() => [])
    if (!out.length) {
      await Promise.all(
        [...this.adapters.values()]
          .filter(p => p.key !== provider && this.breakers.get(p.key)!.available)
          .map(async p => {
            try {
              const r = await withDeadline(this.listFrom(p, 'search', { q: found.title, page: 1, limit: 5 }), this.o.cfg.aggregateTimeoutMs)
              const hit = r.value.find(s => normTitle(s.title) === found.normTitle)
              if (hit) out.push({ provider: p.key, ref: parseId(hit.id).ref })
            } catch {}
          })
      )
    }
    const order = [...this.adapters.keys()]
    return out.filter(s => this.adapters.has(s.provider)).sort((a, b) => order.indexOf(a.provider) - order.indexOf(b.provider))
  }

  async detail(id: string, refresh = false): Promise<{ data: SeriesDetail; meta: ItemMeta }> {
    const { provider, ref } = parseId(id)
    const primary = this.adapter(provider)
    const force = refresh && this.allowForce(`d:${id}`)
    let failure: unknown
    try {
      const r = await withDeadline(this.detailFrom(primary, ref, force), this.o.cfg.primaryTimeoutMs)
      if (!force) this.o.views.hit(provider, ref)
      return { data: r.value, meta: { provider, requestedId: id, fallback: false, source: stateToSource(r.state) } }
    } catch (e) {
      failure = e
    }
    for (const sib of await this.siblingsOf(provider, ref)) {
      try {
        const a = this.adapter(sib.provider)
        const r = await withDeadline(this.detailFrom(a, sib.ref), this.o.cfg.primaryTimeoutMs)
        this.o.views.hit(sib.provider, sib.ref)
        return { data: r.value, meta: { provider: sib.provider, requestedId: id, fallback: true, source: stateToSource(r.state) } }
      } catch {}
    }
    throw failure
  }

  async chapters(id: string, refresh = false): Promise<{ data: ChapterSummary[]; meta: ItemMeta }> {
    const { data, meta } = await this.detail(id, refresh)
    return { data: data.chapters, meta }
  }

  async chapter(id: string, refresh = false): Promise<{ data: ChapterContent; meta: ItemMeta }> {
    const { provider, ref } = parseId(id)
    const primary = this.adapter(provider)
    const force = refresh && this.allowForce(`c:${id}`)
    let failure: unknown
    try {
      const r = await withDeadline(this.chapterFrom(primary, ref, force), this.o.cfg.primaryTimeoutMs)
      this.prefetchChapter(r.value.next)
      return { data: r.value, meta: { provider, requestedId: id, fallback: false, source: stateToSource(r.state) } }
    } catch (e) {
      failure = e
    }
    const saved = await this.o.store.chapterLocal(provider, ref).catch(() => null)
    if (saved) return { data: saved, meta: { provider, requestedId: id, fallback: false, source: 'db' } }
    // Fallback: cari chapter bernomor sama pada seri yang sama di provider lain.
    const known = await this.o.store.findChapter(provider, ref).catch(() => null)
    if (known) {
      for (const sib of await this.siblingsOf(provider, known.seriesRef)) {
        try {
          const a = this.adapter(sib.provider)
          const series = await withDeadline(this.detailFrom(a, sib.ref), this.o.cfg.primaryTimeoutMs)
          const match = series.value.chapters.find(c => sameNumber(c.number, known.number))
          if (!match) continue
          const r = await withDeadline(this.chapterFrom(a, parseId(match.id).ref), this.o.cfg.primaryTimeoutMs)
          return { data: r.value, meta: { provider: sib.provider, requestedId: id, fallback: true, source: stateToSource(r.state) } }
        } catch {}
      }
    }
    throw failure
  }

  /** Cek gambar benar-benar bisa diunduh (status OK + content-type gambar). Mengembalikan jumlah gambar yang gagal. */
  private async probeImages(images: string[], headers: Record<string, string>): Promise<number> {
    let bad = 0
    let next = 0
    const one = async (url: string): Promise<boolean> => {
      for (let attempt = 0; attempt < 2; attempt++) {
        try {
          const res = await fetch(url, { headers: { ...headers, range: 'bytes=0-2047' }, signal: AbortSignal.timeout(12_000), redirect: 'follow' })
          const type = (res.headers.get('content-type') ?? '').toLowerCase()
          const body = await res.arrayBuffer()
          if ((res.status === 200 || res.status === 206) && body.byteLength > 0 && (type.startsWith('image/') || type === 'application/octet-stream')) return true
        } catch {}
      }
      return false
    }
    const worker = async () => {
      while (next < images.length) {
        const url = images[next++]!
        if (!/^https?:\/\//i.test(url) || !(await one(url))) bad++
      }
    }
    await Promise.all(Array.from({ length: Math.min(6, images.length) }, worker))
    return bad
  }

  /**
   * Perbaikan admin: ambil ulang chapter dari upstream (lewati cache), uji semua gambarnya. Kalau semua bisa dimuat -> simpan ke
   * DB + cache (TTL panjang). Kalau gagal -> coba chapter bernomor sama di provider lain dan simpan atas id chapter ini.
   */
  async fixChapter(
    provider: ProviderKey,
    ref: string,
    seriesRef: string,
    number: string
  ): Promise<{ status: 'ok' | 'broken'; source: ProviderKey | null; images: number; bad: number; reason?: string }> {
    const primary = this.adapter(provider)
    const key = `c:${provider}:${ref}`
    const ttl = { fresh: 30 * 24 * 3600_000, stale: this.o.cfg.ttl.chapter.stale }
    const attempt = async (a: ProviderAdapter, r: string) => {
      const c = await this.call(a, () => a.chapter(r))
      if (!c.images.length) return { c, bad: 0, n: 0, reason: 'tidak ada gambar' }
      const headers = { ...a.imageHeaders(), ...(c.imageHeaders ?? {}) }
      const bad = await this.probeImages(c.images, headers)
      return { c: { ...c, imageHeaders: headers }, bad, n: c.images.length, reason: bad ? `${bad}/${c.images.length} gambar gagal dimuat` : undefined }
    }
    let last = { n: 0, bad: 0, reason: 'chapter tidak bisa diambil' }
    try {
      const r = await attempt(primary, ref)
      last = { n: r.n, bad: r.bad, reason: r.reason ?? '' }
      if (!r.reason) {
        await this.o.store.saveChapterImages(provider, ref, r.c, provider)
        await this.o.cache.put(key, r.c, ttl)
        return { status: 'ok', source: provider, images: r.n, bad: 0 }
      }
    } catch (e) {
      last.reason = normalizeError(e).message
    }
    for (const sib of await this.siblingsOf(provider, seriesRef)) {
      try {
        const a = this.adapter(sib.provider)
        const series = await withDeadline(this.detailFrom(a, sib.ref), this.o.cfg.primaryTimeoutMs)
        const match = series.value.chapters.find(c => sameNumber(c.number, number))
        if (!match) continue
        const r = await attempt(a, parseId(match.id).ref)
        if (r.reason) continue
        await this.o.store.saveChapterImages(provider, ref, r.c, sib.provider)
        await this.o.cache.put(key, r.c, ttl)
        return { status: 'ok', source: sib.provider, images: r.n, bad: 0 }
      } catch {}
    }
    return { status: 'broken', source: null, images: last.n, bad: last.bad, reason: last.reason }
  }
}


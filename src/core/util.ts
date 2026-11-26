import { NotFoundError, TimeoutError, UpstreamError, ValidationError } from './errors.js'
import { PROVIDER_KEYS, type ProviderKey } from './types.js'

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))

/** Tag varian di judul yang bukan judul seri: "[Full ver.]", "(Full Version)". Seri bervarian itu = satu komik yang sama. */
const VARIANT_TAG = /[\[(（【]\s*full\s*ver(?:sion)?\.?\s*[\])）】]/giu
export const isVariantTitle = (s: string) => new RegExp(VARIANT_TAG.source, 'iu').test(s)

/** Judul dinormalisasi untuk mencocokkan seri yang sama antar provider (dan varian "[Full ver.]" di provider yang sama). */
export const normTitle = (s: string) =>
  s
    .replace(VARIANT_TAG, ' ')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '')

export const slugify = (s: string) =>
  s
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')

export function isoDate(v: unknown): string | null {
  if (v == null || v === '') return null
  let d: Date
  if (typeof v === 'number') d = new Date(v < 1e11 ? v * 1000 : v)
  else if (/^\d{10,13}$/.test(String(v))) return isoDate(Number(v))
  else d = new Date(String(v))
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

export function numOrNull(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

export const compact = <T>(list: (T | null | undefined)[]): T[] => list.filter((x): x is T => x != null)

export function splitOnce(s: string, sep = ':'): [string, string] {
  const i = s.indexOf(sep)
  if (i < 1 || i === s.length - 1) throw new ValidationError('Invalid chapter id')
  return [s.slice(0, i), s.slice(i + 1)]
}

export function withDeadline<T>(p: Promise<T>, ms: number): Promise<T> {
  let timer: NodeJS.Timeout
  const t = new Promise<never>((_, rej) => {
    timer = setTimeout(() => rej(new TimeoutError()), ms)
  })
  return Promise.race([p, t]).finally(() => clearTimeout(timer))
}

/** Cache promise per-argumen dengan TTL pendek; kegagalan tidak di-cache. */
export function memo<T>(fn: (arg: string) => Promise<T>, ttlMs: number, max = 50) {
  const store = new Map<string, { at: number; p: Promise<T> }>()
  return (arg: string): Promise<T> => {
    const hit = store.get(arg)
    if (hit && Date.now() - hit.at < ttlMs) return hit.p
    const p = fn(arg)
    store.set(arg, { at: Date.now(), p })
    p.catch(() => {
      if (store.get(arg)?.p === p) store.delete(arg)
    })
    if (store.size > max) store.delete(store.keys().next().value as string)
    return p
  }
}

// ---- id unified ----
/** Id unified: `<provider>:<ref asli>` (seri) atau `<provider>:<chapterRef>` (chapter). */
export const sid = (provider: ProviderKey, ref: string) => `${provider}:${ref}`
export const cid = sid

/** Pemisah path / dot-segment (juga bentuk %-encoded-nya) tidak boleh ada di id: id dipakai membentuk URL upstream. */
const ENCODED_SEPARATOR = /%(?:2e|2f|5c|3f|23|00)/i
const isDotSegment = (s: string) => /^\.+$/.test(s)

/** Satu segmen path upstream yang aman (bukan "." / "..", tanpa pemisah/encoding berbahaya), sudah di-encode. */
export function pathSegment(raw: string): string {
  if (!raw || raw.length > 300 || isDotSegment(raw) || ENCODED_SEPARATOR.test(raw) || /[\u0000-\u001f/\\?#]/.test(raw)) {
    throw new ValidationError('Invalid id, expected "<provider>:<id>"')
  }
  return encodeURIComponent(raw)
}

export function parseId(id: string): { provider: ProviderKey; ref: string } {
  const i = id.indexOf(':')
  const provider = id.slice(0, i) as ProviderKey
  const ref = id.slice(i + 1)
  if (
    i < 1 || !PROVIDER_KEYS.includes(provider) || !ref || ref.length > 300 || /[\u0000-\u001f/\\?#]/.test(ref) ||
    ENCODED_SEPARATOR.test(ref) || ref.split(':').some(isDotSegment)
  ) {
    throw new ValidationError('Invalid id, expected "<provider>:<id>"')
  }
  return { provider, ref }
}

// ---- HTTP upstream ----
interface RequestOptions {
  headers: Record<string, string>
  timeoutMs: number
  /** Opsional: batalkan request dari luar (mis. saat mirror lain sudah menang). */
  signal?: AbortSignal
}

/** fetch + timeout; 404 -> NotFoundError, non-2xx -> UpstreamError. */
export async function request(url: string, { headers, timeoutMs, signal }: RequestOptions): Promise<Response> {
  const timeout = AbortSignal.timeout(timeoutMs)
  const res = await fetch(url, { headers, signal: signal ? AbortSignal.any([timeout, signal]) : timeout })
  if (res.status === 404) {
    await res.body?.cancel()
    throw new NotFoundError('Not found upstream')
  }
  if (!res.ok) {
    await res.body?.cancel()
    const hint = [res.headers.get('cf-mitigated') && `cf-mitigated: ${res.headers.get('cf-mitigated')}`, res.headers.get('server') && `server: ${res.headers.get('server')}`].filter(Boolean).join(', ')
    throw new UpstreamError(`HTTP ${res.status}${hint ? ` [${hint}]` : ''}`, res.status)
  }
  return res
}

/** Retry ringan (default 2x ulang, jeda 400ms); 404 tidak diulang. */
export async function retry<T>(fn: () => Promise<T>, retries = 2, delayMs = 400): Promise<T> {
  for (let i = 0; ; i++) {
    try {
      return await fn()
    } catch (e) {
      if (i >= retries || e instanceof NotFoundError) throw e
      await sleep(delayMs)
    }
  }
}

// ---- circuit breaker ----
/** Circuit breaker sederhana: buka setelah N gagal berturut-turut, coba 1 probe setelah cooldown. */
export class CircuitBreaker {
  private failures = 0
  private openedAt = 0
  private probing = false

  constructor(
    private readonly threshold = 5,
    private readonly cooldownMs = 30_000
  ) {}

  get state(): 'closed' | 'open' | 'half-open' {
    if (this.failures < this.threshold) return 'closed'
    return Date.now() - this.openedAt >= this.cooldownMs ? 'half-open' : 'open'
  }

  /** Non-mutating: apakah request kemungkinan diizinkan. */
  get available(): boolean {
    const s = this.state
    return s === 'closed' || (s === 'half-open' && !this.probing)
  }

  /** Mutating: dipanggil tepat sebelum request. */
  canRequest(): boolean {
    const s = this.state
    if (s === 'closed') return true
    if (s === 'half-open' && !this.probing) {
      this.probing = true
      return true
    }
    return false
  }

  success() {
    this.failures = 0
    this.probing = false
  }

  failure() {
    this.failures++
    this.probing = false
    if (this.failures >= this.threshold) this.openedAt = Date.now()
  }
}

// ---- validasi komentar & avatar ----
/** "s:<provider>:<ref>" (seri) atau "c:<provider>:<ref>" (chapter). */
const TARGET_RE = /^[sc]:[a-z0-9_-]{2,20}:[^\s]{1,300}$/i
const MAX_COMMENT = 1000

export function checkTarget(t: unknown): string {
  if (typeof t !== 'string' || !TARGET_RE.test(t) || !PROVIDER_KEYS.includes(t.split(':')[1] as ProviderKey)) throw new ValidationError('Target komentar tidak valid')
  return t
}

/** Rapikan isi komentar: buang karakter kontrol, batasi baris kosong beruntun, wajib 1–1000 karakter. */
export function cleanComment(raw: unknown): string {
  const s = String(raw ?? '')
    .replace(/\r\n?/g, '\n')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  if (!s) throw new ValidationError('Komentar tidak boleh kosong')
  if (s.length > MAX_COMMENT) throw new ValidationError(`Komentar terlalu panjang (maks. ${MAX_COMMENT} karakter)`)
  return s
}

export const MAX_AVATAR_BYTES = 300 * 1024
/** Foto profil hanya JPEG (app mengubah ukuran & mengonversi sebelum upload). */
export const isJpeg = (b: unknown): b is Buffer => Buffer.isBuffer(b) && b.length > 4 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff

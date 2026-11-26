import { createHash } from 'node:crypto'
import type { FastifyBaseLogger } from 'fastify'
import type { ChapterSummary } from '../core/types.js'

export const pushTopic = (seriesId: string) => 's_' + createHash('sha1').update(seriesId).digest('hex').slice(0, 24)

const num = (s: string) => (Number.isFinite(parseFloat(s)) ? parseFloat(s) : -Infinity)

/** Chapter terbaru dari daftar chapter baru (nomor terbesar; bila tidak ada nomor, yang terakhir). */
export function newestOf(chapters: ChapterSummary[]): ChapterSummary | undefined {
  return chapters.reduce<ChapterSummary | undefined>((best, c) => (!best || num(c.number) >= num(best.number) ? c : best), undefined)
}

interface Messaging {
  send(m: unknown): Promise<string>
}

export interface PushSettings {
  enabled: boolean
  newChapter: boolean
}
export interface PushLogEntry {
  kind: 'broadcast' | 'chapter'
  title: string
  body: string
  seriesId: string | null
  ok: boolean
  error: string | null
}
/** Penyimpanan pengaturan & riwayat (diimplementasi PgStore). */
export interface PushRepo {
  pushSettings(): Promise<PushSettings>
  logPush(e: PushLogEntry): Promise<void>
}

/**
 * Push realtime lewat Firebase Cloud Messaging. Aktif hanya bila FIREBASE_SERVICE_ACCOUNT diisi
 * (JSON service account, mentah atau base64); pengaturan hidup/mati diatur dari panel admin.
 *  - chapter baru: pesan data-only ke topic komik (`s_<sha1>`), app yang mem-bookmark komik itu sudah subscribe
 *  - broadcast manual dari admin: topic `all`, semua perangkat subscribe
 */
export class PushService {
  private messaging?: Promise<Messaging | null>
  private cached?: { at: number; v: PushSettings }

  constructor(private readonly raw: string, private readonly log: FastifyBaseLogger, private readonly repo: PushRepo) {}

  get configured() {
    return !!this.raw
  }

  invalidate() {
    this.cached = undefined
  }

  async settings(): Promise<PushSettings> {
    if (this.cached && Date.now() - this.cached.at < 15_000) return this.cached.v
    const v = await this.repo.pushSettings().catch(() => ({ enabled: true, newChapter: true }))
    this.cached = { at: Date.now(), v }
    return v
  }

  private init(): Promise<Messaging | null> {
    this.messaging ??= (async () => {
      try {
        const text = this.raw.trim().startsWith('{') ? this.raw : Buffer.from(this.raw, 'base64').toString('utf8')
        const cred = JSON.parse(text)
        const [{ initializeApp, cert, getApps }, { getMessaging }] = await Promise.all([import('firebase-admin/app'), import('firebase-admin/messaging')])
        const app = getApps()[0] ?? initializeApp({ credential: cert(cred) })
        return getMessaging(app) as unknown as Messaging
      } catch (e) {
        this.log.warn({ err: (e as Error).message }, 'push: firebase init failed, push dinonaktifkan')
        return null
      }
    })()
    return this.messaging
  }

  async newChapter(series: { id: string; title: string }, chapters: ChapterSummary[]): Promise<void> {
    if (!this.configured) return
    const ch = newestOf(chapters)
    if (!ch) return
    const st = await this.settings()
    if (!st.enabled || !st.newChapter) return
    const m = await this.init()
    if (!m) return
    try {
      await m.send({
        topic: pushTopic(series.id),
        data: { type: 'chapter', seriesId: series.id, title: series.title, chapter: ch.number, chapterId: ch.id },
        android: { priority: 'high', ttl: 24 * 3600 * 1000 }
      })
    } catch (e) {
      const err = (e as Error).message
      this.log.warn({ series: series.id, err }, 'push: send failed')
      await this.repo.logPush({ kind: 'chapter', title: series.title, body: `Chapter ${ch.number}`, seriesId: series.id, ok: false, error: err }).catch(() => undefined)
    }
  }

  /** Kirim notifikasi manual ke semua perangkat. Melempar Error berpesan jelas bila push belum siap / dimatikan. */
  async broadcast(p: { title: string; body: string; seriesId?: string | null }): Promise<void> {
    if (!this.configured) throw new Error('FIREBASE_SERVICE_ACCOUNT belum diisi di server')
    const st = await this.settings()
    if (!st.enabled) throw new Error('Push sedang dimatikan di pengaturan')
    const m = await this.init()
    if (!m) throw new Error('Firebase gagal diinisialisasi, cek FIREBASE_SERVICE_ACCOUNT')
    const entry = { kind: 'broadcast' as const, title: p.title, body: p.body, seriesId: p.seriesId ?? null }
    try {
      const data: Record<string, string> = { type: 'broadcast', title: p.title, body: p.body }
      if (p.seriesId) data.seriesId = p.seriesId
      await m.send({ topic: 'all', data, android: { priority: 'high', ttl: 24 * 3600 * 1000 } })
      await this.repo.logPush({ ...entry, ok: true, error: null }).catch(() => undefined)
    } catch (e) {
      const err = (e as Error).message
      await this.repo.logPush({ ...entry, ok: false, error: err }).catch(() => undefined)
      throw new Error(`Gagal mengirim: ${err}`)
    }
  }
}

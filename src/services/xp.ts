import type { Pool } from 'pg'
import { ValidationError } from '../core/errors.js'
import { isPremiumSql, premiumXp } from '../core/premium.js'
import { PROVIDER_KEYS, type ProviderKey } from '../core/types.js'
import { CLIENT_KINDS, XP_RULES, levelFor, streakOf, weekWindow, type Streak, type XpKind } from '../core/xp.js'

const REF_RE = /^[a-z0-9_-]{2,20}:[^\s]{1,300}$/i
/** Tanggal hari ini di WIB (UTC+7), dipakai sebagai ref untuk bonus harian. */
const wibDay = (now = Date.now()) => new Date(now + 7 * 3600_000).toISOString().slice(0, 10)

/** Pemberian XP: tiap (user, jenis, ref) hanya sekali + batas harian per jenis; dicatat di xp_events. */
const BOARD_TTL_MS = 8_000
const BOARD_CACHE_MAX = 200

export class XpService {
  private readonly boardCache = new Map<string, { until: number; p: Promise<Leaderboard> }>()

  constructor(
    private readonly pool: Pool,
    private readonly ownerEmails: string[] = []
  ) {}

  /**
   * Beri XP bila belum pernah & kuota harian belum habis. Mengembalikan XP yang didapat (0 = tidak ada).
   * Premium aktif mendapat XP lebih besar (lihat PREMIUM_XP_MULT); kuota harian per jenis tetap sama untuk semua.
   */
  async award(userId: number, kind: XpKind, ref: string): Promise<number> {
    const rule = XP_RULES[kind]
    const { rows } = await this.pool.query(
      `WITH p AS (
         SELECT ${isPremiumSql()} AS active FROM users WHERE id = $1::int
       ), ins AS (
         INSERT INTO xp_events (user_id, kind, ref, xp)
         SELECT $1::int, $2::text, $3::text, CASE WHEN (SELECT active FROM p) THEN $6::int ELSE $4::int END
         WHERE (SELECT count(*) FROM xp_events WHERE user_id = $1::int AND kind = $2::text AND day = ((now() AT TIME ZONE 'Asia/Jakarta')::date)) < $5::int
         ON CONFLICT (user_id, kind, ref) DO NOTHING
         RETURNING xp
       )
       UPDATE users SET xp = xp + (SELECT xp FROM ins) WHERE id = $1::int AND EXISTS (SELECT 1 FROM ins) RETURNING (SELECT xp FROM ins) AS gained`,
      [userId, kind, ref, rule.xp, rule.perDay, premiumXp(rule.xp)]
    )
    return rows.length ? Number(rows[0].gained ?? rule.xp) : 0
  }

  /** Laporan dari app (baca chapter, tambah pustaka, buka harian). Jenis & ref divalidasi. */
  async report(userId: number, type: unknown, ref: unknown): Promise<number> {
    if (!CLIENT_KINDS.includes(type as never)) throw new ValidationError('Jenis aktivitas tidak valid')
    const kind = type as (typeof CLIENT_KINDS)[number]
    if (kind === 'daily') return this.award(userId, 'daily', wibDay())
    if (typeof ref !== 'string' || !REF_RE.test(ref) || !PROVIDER_KEYS.includes(ref.split(':')[0] as ProviderKey)) throw new ValidationError('Referensi tidak valid')
    if (kind === 'read') await this.markReadDay(userId) // streak naik walau XP-nya 0 (chapter lama / kuota habis)
    return this.award(userId, kind, ref)
  }

  /** Catat bahwa pengguna membaca hari ini (WIB); aman dipanggil berulang. */
  private async markReadDay(userId: number): Promise<void> {
    await this.pool.query(
      `INSERT INTO read_days (user_id, day) VALUES ($1::int, (now() AT TIME ZONE 'Asia/Jakarta')::date) ON CONFLICT DO NOTHING`,
      [userId]
    )
  }

  /** Streak membaca pengguna saat ini. */
  async streak(userId: number, now = Date.now()): Promise<Streak> {
    const { rows } = await this.pool.query(
      `SELECT to_char(day, 'YYYY-MM-DD') AS d FROM read_days WHERE user_id = $1::int ORDER BY day DESC LIMIT 1000`,
      [userId]
    )
    return streakOf(rows.map((r: { d: string }) => r.d), now)
  }

  /**
   * Papan peringkat XP mingguan (Senin 00.00 WIB → Senin berikutnya): jumlah XP dari buku besar `xp_events` pada minggu berjalan.
   * Seri skor: yang lebih dulu mencapai skor itu di atas. Akun yang diblokir tidak ikut. `meId` (opsional) → posisi peminta, walau di luar `limit`.
   */
  async leaderboard(meId: number | null, limit = 50, now = Date.now()): Promise<Leaderboard> {
    const win = weekWindow(now)
    const key = `${win.startDay}|${limit}|${meId ?? 0}`
    const hit = this.boardCache.get(key)
    const t = Date.now()
    if (hit && t < hit.until) return hit.p
    const p = this.computeBoard(meId, limit, now, win)
    this.boardCache.set(key, { until: t + BOARD_TTL_MS, p })
    p.catch(() => this.boardCache.delete(key))
    if (this.boardCache.size > BOARD_CACHE_MAX) for (const [k, v] of this.boardCache) if (t >= v.until || this.boardCache.size > BOARD_CACHE_MAX) this.boardCache.delete(k)
    return p
  }

  private async computeBoard(meId: number | null, limit: number, now: number, win: ReturnType<typeof weekWindow>): Promise<Leaderboard> {
    const { rows } = await this.pool.query(
      `WITH w AS (
         SELECT e.user_id, sum(e.xp)::int AS wxp, max(e.created_at) AS last_at
           FROM xp_events e WHERE e.day >= $1::date GROUP BY e.user_id
       ), r AS (
         SELECT w.user_id, w.wxp, u.username, u.display_name, u.xp, u.email, u.verified, ${isPremiumSql('u')} AS premium, u.verified_hidden,
                CASE WHEN u.avatar IS NULL THEN 0 ELSE u.avatar_v END AS avatar_v,
                row_number() OVER (ORDER BY w.wxp DESC, w.last_at ASC, w.user_id ASC)::int AS rank
           FROM w JOIN users u ON u.id = w.user_id WHERE u.banned_at IS NULL
       )
       SELECT r.*, (SELECT count(*) FROM r)::int AS total FROM r WHERE r.rank <= $2::int OR r.user_id = $3::int ORDER BY r.rank`,
      [win.startDay, limit, meId ?? 0]
    )
    const toEntry = (r: Record<string, unknown>): RankEntry => {
      const email = String(r.email ?? '').toLowerCase()
      const isOwner = !!email && this.ownerEmails.includes(email)
      return {
        rank: Number(r.rank),
        id: Number(r.user_id),
        username: String(r.username),
        displayName: String(r.display_name ?? ''),
        avatarV: Number(r.avatar_v ?? 0),
        level: levelFor(Number(r.xp ?? 0)).level,
        verified: (r.verified === true || isOwner) && r.verified_hidden !== true,
        isOwner,
        premium: r.premium === true,
        weekXp: Number(r.wxp)
      }
    }
    const all = rows.map(toEntry)
    const mine = meId ? all.find(e => e.id === meId) : undefined
    return {
      weekStart: win.startAt,
      resetAt: win.endAt,
      now,
      total: rows.length ? Number(rows[0].total) : 0,
      items: all.filter(e => e.rank <= limit),
      me: mine ? { rank: mine.rank, weekXp: mine.weekXp } : null
    }
  }
}

interface RankEntry {
  rank: number
  id: number
  username: string
  displayName: string
  avatarV: number
  level: number
  verified: boolean
  isOwner: boolean
  premium: boolean
  weekXp: number
}

interface Leaderboard {
  /** awal minggu (Senin 00.00 WIB), epoch ms */
  weekStart: number
  /** waktu reset berikutnya (Senin 00.00 WIB), epoch ms */
  resetAt: number
  /** jam server saat dihitung, epoch ms (untuk hitung mundur tanpa bergantung jam perangkat) */
  now: number
  /** jumlah pengguna yang punya XP minggu ini */
  total: number
  items: RankEntry[]
  /** posisi peminta bila login & sudah punya XP minggu ini */
  me: { rank: number; weekXp: number } | null
}

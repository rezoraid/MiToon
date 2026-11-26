/** Aturan XP & kurva level (murni, mudah diuji). */

export const XP_RULES = {
  /** membaca chapter (sekali per chapter) */
  read: { xp: 10, perDay: 20 },
  /** komentar atau balasan */
  comment: { xp: 5, perDay: 10 },
  /** menambah seri ke pustaka (sekali per seri) */
  bookmark: { xp: 3, perDay: 5 },
  /** membuka aplikasi (sekali per hari) */
  daily: { xp: 5, perDay: 1 },
  /** komentarmu disukai orang lain (sekali per penyuka) */
  liked: { xp: 1, perDay: 20 }
} as const
export type XpKind = keyof typeof XP_RULES
/** Jenis yang boleh dilaporkan app lewat POST /xp/event (komentar & like dihitung server sendiri). */
export const CLIENT_KINDS = ['read', 'bookmark', 'daily'] as const

export const MAX_LEVEL = 10000

/**
 * XP yang dibutuhkan untuk naik dari level L ke L+1 (k = L-1): 20 + 5k + k²/8 (bilangan bulat).
 * Awal-awal sangat murah, makin tinggi makin mahal:
 * L1→2: 20 · L5→6: 42 · L10→11: 75 · L20→21: 160 · L50→51: 565 · L100→101: 1.740.
 * Total XP: Lv. 10 = 384 · Lv. 50 = 11.604 · Lv. 100 = 66.035 · Lv. 10.000 ≈ 41,9 miliar.
 */
export const levelCost = (level: number): number => {
  const k = Math.max(0, Math.floor(level) - 1)
  return 20 + 5 * k + Math.floor((k * k) / 8)
}

/** CUM[L] = total XP minimum untuk mencapai level L (CUM[1] = 0). Dihitung sekali; semua pencarian level O(log n). */
const CUM: number[] = (() => {
  const a = new Array<number>(MAX_LEVEL + 1).fill(0)
  for (let l = 2; l <= MAX_LEVEL; l++) a[l] = a[l - 1] + levelCost(l - 1)
  return a
})()

/** Total XP minimum untuk mencapai level tertentu (kebalikan [levelFor]); level 1 = 0 XP. */
export function xpForLevel(level: number): number {
  return CUM[Math.max(1, Math.min(MAX_LEVEL, Math.floor(Number(level) || 1)))]
}

/** XP minimum Lv. 10.000 = batas atas XP yang dipakai panel admin. */
export const XP_MAX = CUM[MAX_LEVEL]

interface LevelInfo {
  level: number
  /** XP yang sudah terkumpul di level ini */
  into: number
  /** XP yang dibutuhkan untuk naik dari level ini */
  need: number
}

export function levelFor(xp: number): LevelInfo {
  const x = Math.max(0, Math.floor(Number(xp) || 0))
  let lo = 1
  let hi = MAX_LEVEL
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (CUM[mid] <= x) lo = mid
    else hi = mid - 1
  }
  const need = levelCost(lo)
  return { level: lo, into: Math.min(x - CUM[lo], lo >= MAX_LEVEL ? need : Infinity), need }
}

const WIB_MS = 7 * 3600_000
const DAY_MS = 86_400_000

interface WeekWindow {
  /** Tanggal Senin minggu ini (WIB), "YYYY-MM-DD" — cocok dengan kolom xp_events.day */
  startDay: string
  /** Awal minggu (Senin 00.00 WIB), epoch ms */
  startAt: number
  /** Awal minggu berikutnya = waktu reset papan peringkat, epoch ms */
  endAt: number
}

/** Jendela papan peringkat mingguan: Senin 00.00 WIB sampai Senin berikutnya (murni, mudah diuji). */
export function weekWindow(now = Date.now()): WeekWindow {
  const wall = now + WIB_MS // "jam dinding" WIB dibaca sebagai UTC
  const dayStart = Math.floor(wall / DAY_MS) * DAY_MS
  const sinceMonday = (new Date(dayStart).getUTCDay() + 6) % 7 // Senin = 0
  const start = dayStart - sinceMonday * DAY_MS
  return { startDay: new Date(start).toISOString().slice(0, 10), startAt: start - WIB_MS, endAt: start + 7 * DAY_MS - WIB_MS }
}

export interface Streak {
  /** jumlah hari berturut-turut membaca; tetap dihitung bila kemarin membaca & hari ini belum (masih bisa diselamatkan) */
  current: number
  /** sudah membaca hari ini (WIB) */
  readToday: boolean
}

/**
 * Streak membaca dari daftar hari (WIB, "YYYY-MM-DD", urutan bebas). Murni, mudah diuji.
 * Hari ini sudah baca -> hitung mundur dari hari ini; belum tapi kemarin baca -> hitung mundur dari kemarin;
 * selain itu streak putus (0).
 */
export function streakOf(days: readonly string[], now = Date.now()): Streak {
  const toDay = (d: string) => Math.floor(Date.parse(`${d}T00:00:00Z`) / DAY_MS)
  const set = new Set(days.map(toDay).filter(Number.isFinite))
  const today = Math.floor((now + WIB_MS) / DAY_MS)
  const readToday = set.has(today)
  let d = readToday ? today : today - 1
  let current = 0
  while (set.has(d)) { current++; d-- }
  return { current, readToday }
}

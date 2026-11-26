/** Paket, metode bayar, dan aturan Premium (murni, mudah diuji). Harga SELALU diambil dari sini, bukan dari klien. */

/** Pengali bonus XP untuk Premium (dibulatkan ke atas per perolehan). */
export const PREMIUM_XP_MULT = 1.5
export const premiumXp = (base: number): number => Math.ceil(base * PREMIUM_XP_MULT)

/** Harga dasar per bulan; dipakai menghitung "hemat". */
const BASE_MONTH = 7000

export interface Plan {
  id: string
  months: number
  price: number
  label: string
}
export const PLANS: readonly Plan[] = [
  { id: '1m', months: 1, price: 7000, label: '1 Bulan' },
  { id: '3m', months: 3, price: 18000, label: '3 Bulan' },
  { id: '12m', months: 12, price: 60000, label: '12 Bulan' }
]
export const planById = (id: unknown): Plan | undefined => PLANS.find(p => p.id === id)
export const planSaving = (p: Plan): number => Math.max(0, BASE_MONTH * p.months - p.price)

export type MethodGroup = 'qris' | 'ewallet' | 'bank'
export interface PayMethod {
  id: string
  name: string
  group: MethodGroup
  /** minimal nominal yang diterima Sociabuzz untuk metode ini */
  min: number
}
/** Metode yang ditawarkan di app. (OVO butuh nomor HP, jadi tidak ditawarkan.) */
export const METHODS: readonly PayMethod[] = [
  { id: 'qris', name: 'QRIS', group: 'qris', min: 1000 },
  { id: 'gopay', name: 'GoPay', group: 'ewallet', min: 1000 },
  { id: 'dana', name: 'DANA', group: 'ewallet', min: 1000 },
  { id: 'shopeepay_idr', name: 'ShopeePay', group: 'ewallet', min: 1000 },
  { id: 'linkaja', name: 'LinkAja', group: 'ewallet', min: 1000 },
  { id: 'bca', name: 'BCA VA', group: 'bank', min: 10000 },
  { id: 'bri', name: 'BRI VA', group: 'bank', min: 10000 },
  { id: 'bni', name: 'BNI VA', group: 'bank', min: 10000 },
  { id: 'mandiri', name: 'Mandiri VA', group: 'bank', min: 10000 }
]
export const methodById = (id: unknown): PayMethod | undefined => METHODS.find(m => m.id === id)
export const methodsFor = (price: number): PayMethod[] => METHODS.filter(m => price >= m.min)

/** Id tema profil & frame avatar yang valid (tampilannya digambar di app). */
export const THEMES = ['ocean', 'sakura', 'aurora', 'sunset', 'gold', 'violet'] as const
export const FRAMES = ['gold', 'neon', 'sakura', 'flame', 'ice', 'emerald'] as const

export const MAX_GIF_AVATAR_BYTES = 2 * 1024 * 1024
export const MAX_HEADER_BYTES = 450 * 1024
export const MAX_HEADER_BLUR = 25

/** Fragmen SQL "Premium sedang aktif" untuk tabel users (alias opsional): flag permanen ATAU langganan belum habis. */
export const isPremiumSql = (alias = ''): string => {
  const p = alias ? `${alias}.` : ''
  return `(${p}premium OR (${p}premium_until IS NOT NULL AND ${p}premium_until > now()))`
}

export const isGif = (b: unknown): b is Buffer =>
  Buffer.isBuffer(b) && b.length > 10 && (b.subarray(0, 6).toString('latin1') === 'GIF87a' || b.subarray(0, 6).toString('latin1') === 'GIF89a')

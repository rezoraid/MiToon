import { createHmac, timingSafeEqual } from 'node:crypto'

/** Primitif auth tanpa dependensi: token HS256 (JWT). Murni, mudah diuji. */

const b64u = (v: Buffer | string) => Buffer.from(v).toString('base64url')

export function signToken(sub: number, secret: string, ttlSec: number, now = Date.now(), tv = 0): string {
  const head = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const iat = Math.floor(now / 1000)
  const body = b64u(JSON.stringify({ sub, iat, exp: iat + ttlSec, tv }))
  const sig = createHmac('sha256', secret).update(`${head}.${body}`).digest('base64url')
  return `${head}.${body}.${sig}`
}

/** `tv` = versi token akun; naik saat "keluar dari semua perangkat" sehingga token lama langsung mati. Token lama tanpa `tv` dianggap 0. */
export function verifyToken(token: string, secret: string, now = Date.now()): { sub: number; tv: number } | null {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  const [h, b, s] = parts
  const want = createHmac('sha256', secret).update(`${h}.${b}`).digest()
  const got = Buffer.from(s, 'base64url')
  if (got.length !== want.length || !timingSafeEqual(got, want)) return null
  try {
    const p = JSON.parse(Buffer.from(b, 'base64url').toString())
    if (typeof p.sub !== 'number' || typeof p.exp !== 'number' || p.exp * 1000 < now) return null
    return { sub: p.sub, tv: typeof p.tv === 'number' ? p.tv : 0 }
  } catch {
    return null
  }
}

// ---- validasi input (pesan Indonesia, ditampilkan langsung di app) ----
export const USERNAME_RE = /^[A-Za-z0-9_.]{3,20}$/
export const USERNAME_MSG = 'Username 3–20 karakter: huruf, angka, titik, atau garis bawah'

/** Username otomatis dari nama bebas (dari email): hanya karakter yang diizinkan, 3–20 karakter. */
export function slugUsername(raw: string): string {
  const s = raw.replace(/[^A-Za-z0-9_.]+/g, '').slice(0, 16)
  return s.length >= 3 ? s : `user${s}`.slice(0, 16)
}

/** Sisa waktu cooldown yang ramah dibaca: "6 hari 3 jam", "5 jam 12 menit", "12 menit", "<1 menit". */
export function formatCooldown(sec: number): string {
  const m = Math.ceil(sec / 60)
  const d = Math.floor(m / 1440)
  const h = Math.floor((m % 1440) / 60)
  const mm = m % 60
  if (d > 0) return h > 0 ? `${d} hari ${h} jam` : `${d} hari`
  if (h > 0) return mm > 0 ? `${h} jam ${mm} menit` : `${h} jam`
  return m > 0 ? `${m} menit` : 'kurang dari 1 menit'
}

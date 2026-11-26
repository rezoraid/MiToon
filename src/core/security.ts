/**
 * Kebijakan keamanan HTTP yang murni (tanpa dependensi) supaya mudah diuji.
 */

/**
 * Nilai `trustProxy` Fastify dari env TRUST_PROXY.
 *  - kosong        -> 1 hop (satu reverse proxy tepercaya di depan app: Railway/Vercel/Nginx)
 *  - "false"       -> tidak percaya header X-Forwarded-*
 *  - "2"           -> jumlah hop tepercaya
 *  - "10.0.0.0/8,…" -> daftar IP/CIDR proxy tepercaya
 * `true` (percaya semua hop) sengaja tidak tersedia: klien bisa memalsukan X-Forwarded-For dan melewati rate limit.
 */
export function parseTrustProxy(raw: string | undefined): boolean | number | string[] {
  const v = (raw ?? '').trim()
  if (!v) return 1
  if (/^(false|0)$/i.test(v)) return false
  if (/^\d{1,2}$/.test(v)) return Math.max(1, Number(v))
  const list = v.split(',').map(s => s.trim()).filter(Boolean)
  return list.length ? list : 1
}

interface HeaderSink {
  header(name: string, value: string): unknown
}

/** Header pengaman standar untuk semua respons API. */
export function applySecurityHeaders(reply: HeaderSink): void {
  reply.header('x-content-type-options', 'nosniff')
  reply.header('x-frame-options', 'DENY')
  reply.header('referrer-policy', 'no-referrer')
  reply.header('strict-transport-security', 'max-age=15552000; includeSubDomains')
  reply.header('cross-origin-opener-policy', 'same-origin')
  reply.header('permissions-policy', 'camera=(), microphone=(), geolocation=()')
}

/** Isi unggahan pengguna (foto profil) tidak boleh dieksekusi/dirender sebagai halaman bila dibuka langsung di browser. */
export const USER_CONTENT_CSP = "default-src 'none'; sandbox"

import { parseTrustProxy } from './core/security.js'
import { PROVIDER_KEYS } from './core/types.js'

const env = process.env
const min = (n: number) => n * 60_000
const hour = (n: number) => n * 3_600_000

/** true di Vercel (VERCEL=1 otomatis): tanpa timer latar, tulis DB inline, tanpa migrate saat runtime */
const serverless = env.VERCEL === '1'

/** Hanya DATABASE_URL & REDIS_URL yang wajib diisi; ADMIN_TOKEN opsional (mengaktifkan endpoint admin pengumuman). */
export const config = {
  serverless,
  port: Number(env.PORT) || 3000,
  host: '0.0.0.0',
  logLevel: 'info',
  /** Jumlah reverse proxy tepercaya (env TRUST_PROXY: angka, "false", atau daftar CIDR). `true` dilarang: X-Forwarded-For bisa dipalsukan untuk lolos rate limit. */
  trustProxy: parseTrustProxy(env.TRUST_PROXY),
  databaseUrl: env.DATABASE_URL ?? 'postgres://scraper:scraper@localhost:5432/scraper',
  redisUrl: env.REDIS_URL ?? 'redis://localhost:6379',
  adminToken: env.ADMIN_TOKEN ?? '',
  /** Push realtime (FCM): isi FIREBASE_SERVICE_ACCOUNT dengan JSON service account (mentah atau base64). Kosong = push nonaktif. */
  firebaseServiceAccount: env.FIREBASE_SERVICE_ACCOUNT ?? '',
  /** Akun & login. JWT_SECRET wajib diisi di produksi; Google aktif hanya bila GOOGLE_CLIENT_ID diisi. */
  auth: {
    jwtSecret: env.JWT_SECRET ?? '',
    tokenTtlSec: 90 * 24 * 3600,
    googleClientId: env.GOOGLE_CLIENT_ID ?? '',
    /** Email owner (pisah koma). Akun yang masuk lewat Google dengan email terverifikasi ini otomatis dapat centang verified. */
    ownerEmails: (env.OWNER_EMAILS ?? '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean),
  },
  /** Premium: pembayaran lewat Sociabuzz (akun penerima donasi). PREMIUM_ENABLED=0 mematikan pembelian. */
  premium: {
    enabled: env.PREMIUM_ENABLED !== '0',
    sociabuzzUsername: (env.SOCIABUZZ_USERNAME ?? 'reyzdesu').trim()
  },
  pgPoolMax: serverless ? 2 : 10,
  autoMigrate: !serverless,
  providers: [...PROVIDER_KEYS],
  /** Per IP. Operator seluler memakai CGNAT (banyak pengguna satu IP), dan Home saja memicu ±25 request, jadi 120 mudah kena 429. */
  rateLimitMax: 300,
  /** Batas total menunggu satu provider saat fan-out. Lebih ketat = respons lebih cepat saat upstream lambat. */
  aggregateTimeoutMs: 5000,
  /** Daftar (latest/search): setelah provider PERTAMA menjawab dengan data, provider lain yang lambat hanya ditunggu sebatas ini (ms). 0 = tunggu semua. */
  listGraceMs: 900,
  /** Saat satu chapter dibuka, chapter berikutnya diambil lebih dulu di background (baca lanjut = instan). */
  prefetchNextChapter: true,
  primaryTimeoutMs: 10000,
  warmer: {
    enabled: true,
    /** Lebih sering menghangatkan list Home/Explore supaya cache selalu fresh saat app dibuka. */
    intervalMs: 60_000,
    enrich: 20,
    maintainEvery: 4,
    /** Crawler katalog: genre hanya dibaca dari DB, jadi DB diisi dalam-dalam (banyak halaman tiap sumber) supaya filter genre berisi banyak seri. */
    crawl: { pages: 15, size: 50, firstDelayMs: 20_000, everyMs: hour(6), enrich: 40, seeds: ['action', 'adventure', 'comedy', 'drama', 'fantasy', 'horror', 'isekai', 'martial arts', 'mystery', 'romance', 'school life', 'sci-fi', 'slice of life', 'supernatural'] }
  },
  /** fresh = dilayani langsung, stale = dilayani + refresh background / saat upstream gagal */
  ttl: {
    search: { fresh: min(5), stale: hour(1) },
    /** List home sering dibuka: fresh sedikit lebih lama supaya hit cache, stale tetap 1 jam. */
    latest: { fresh: min(3), stale: hour(1) },
    detail: { fresh: min(20), stale: hour(24 * 7) },
    /** Gambar chapter nyaris tidak pernah berubah: fresh lama + stale panjang = hampir selalu cache-hit. */
    chapter: { fresh: hour(6), stale: hour(24 * 7) },
    db: { fresh: min(2), stale: hour(1) },
    genres: { fresh: min(15), stale: hour(2) }
  }
}
export type Config = typeof config

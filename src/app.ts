import { createHash } from 'node:crypto'
import compress from '@fastify/compress'
import rateLimit from '@fastify/rate-limit'
import Fastify from 'fastify'
import { Redis } from 'ioredis'
import type { Config } from './config.js'
import { Cache } from './core/cache.js'
import { AppError, dbErrorStatus } from './core/errors.js'
import { applySecurityHeaders } from './core/security.js'
import { createPool, migrate } from './db/pool.js'
import { PgStore } from './db/store.js'
import { createProviders } from './providers/index.js'
import { adminRoutes } from './routes/admin.js'
import { announcementRoutes } from './routes/announcements.js'
import { authRoutes } from './routes/auth.js'
import { catalogRoutes } from './routes/catalog.js'
import { chatRoutes } from './routes/chat.js'
import { commentRoutes } from './routes/comments.js'
import { libraryRoutes } from './routes/library.js'
import { notificationRoutes } from './routes/notifications.js'
import { premiumRoutes } from './routes/premium.js'
import { readingRoutes } from './routes/reading.js'
import { seriesRoutes } from './routes/series.js'
import { systemRoutes } from './routes/system.js'
import { xpRoutes } from './routes/xp.js'
import { AnnouncementRepo } from './services/announcements.js'
import { AuthService } from './services/auth.js'
import { ChatService } from './services/chat.js'
import { CommentService } from './services/comments.js'
import { LibraryService } from './services/library.js'
import { NotificationService } from './services/notifications.js'
import { PremiumService } from './services/premium.js'
import { PushService } from './services/push.js'
import { ReadingService } from './services/reading.js'
import { ScraperService } from './services/scraper.js'
import { SociabuzzGateway } from './services/sociabuzz.js'
import { ViewCounter } from './services/views.js'
import { Warmer } from './services/warmer.js'
import { XpService } from './services/xp.js'

/** Composition root: merangkai semua dependensi. */
export async function createApp(cfg: Config, opts: { docs?: boolean } = {}) {
  // Swagger publik hanya di non-produksi, kecuali ENABLE_DOCS=1
  const docs = opts.docs ?? (process.env.NODE_ENV !== 'production' || process.env.ENABLE_DOCS === '1')
  const app = Fastify({
    logger: { level: cfg.logLevel },
    // number -> fungsi: tipe opsi Fastify tidak menerima number; semantik sama (percaya `n` hop terdekat)
    trustProxy:
      typeof cfg.trustProxy === 'number'
        ? ((_addr: string, hop: number) => hop < (cfg.trustProxy as number))
        : cfg.trustProxy,
    maxParamLength: 300,
    keepAliveTimeout: 65_000, // > idle timeout proxy (Railway/Nginx ~60s): koneksi dipakai ulang, tanpa handshake TLS ulang
    connectionTimeout: 30_000
  })
  const log = app.log

  // ---- PostgreSQL ----
  const pool = createPool(cfg)
  if (cfg.autoMigrate) {
    const applied = await migrate(pool)
    if (applied.length) log.info({ applied }, 'migrations applied')
  }
  const store = new PgStore(pool)
  const push = new PushService(cfg.firebaseServiceAccount, app.log, store)
  if (push.configured) store.onNewChapters((s, chapters) => push.newChapter(s, chapters))

  // ---- Redis (opsional saat runtime: jika mati, cache dilewati) ----
  const redis = new Redis(cfg.redisUrl, {
    maxRetriesPerRequest: 1,
    enableOfflineQueue: false,
    connectTimeout: 2_000,
    retryStrategy: n => Math.min(n * 200, 3_000)
  })
  let lastRedisLog = 0
  redis.on('error', e => {
    if (Date.now() - lastRedisLog > 10_000) {
      lastRedisLog = Date.now()
      log.warn({ err: e.message }, 'redis error')
    }
  })
  // Koneksi Redis & sinkron provider berjalan paralel (memotong cold start).
  const adapters = createProviders(cfg.providers)
  await Promise.all([
    Promise.race([new Promise(r => redis.once('ready', r)), new Promise(r => setTimeout(r, 3_000))]),
    store.syncProviders(adapters.map(a => ({ key: a.key, name: a.name, baseUrl: a.baseUrl })))
  ])

  // ---- domain ----
  const views = new ViewCounter(store, log, 15_000, cfg.serverless)
  const svc = new ScraperService({ adapters, cache: new Cache(redis, log), store, views, log, cfg })
  const warmer = new Warmer(svc, store, cfg.providers, log, { ...cfg.warmer, watchNew: push.configured })

  // ---- header keamanan di semua respons ----
  app.addHook('onSend', async (_req, reply, payload) => {
    applySecurityHeaders(reply)
    return payload
  })

  // ---- ETag (dihitung sebelum kompresi) ----
  app.addHook('onSend', async (req, reply, payload) => {
    if (req.method !== 'GET' || reply.statusCode !== 200 || typeof payload !== 'string') return payload
    const etag = `W/"${createHash('sha1').update(payload).digest('base64url').slice(0, 22)}"`
    reply.header('etag', etag)
    if (req.headers['if-none-match'] === etag) {
      reply.code(304)
      return ''
    }
    return payload
  })
  await app.register(compress, { threshold: 1024, encodings: ['gzip', 'deflate'] })

  // ---- rate limit: counter di memori proses (tanpa round-trip Redis di setiap request) ----
  await app.register(rateLimit, {
    max: cfg.rateLimitMax,
    timeWindow: '1 minute',
    cache: 10_000,
    skipOnError: true,
    allowList: req => req.url.startsWith('/docs') || req.url === '/health'
  })

  // ---- dokumentasi (dimuat dinamis: instance API murni tidak menanggung biaya swagger saat start) ----
  if (docs) {
    const [{ default: swagger }, { default: swaggerUi }] = await Promise.all([import('@fastify/swagger'), import('@fastify/swagger-ui')])
    await app.register(swagger, {
      openapi: {
        info: {
          title: 'Unified Scraper API',
          version: '1.0.0',
          description:
            'Satu API untuk provider Shinigami dan Lumos dengan format data seragam.\n\n' +
            'Id unified: `<provider>:<id asli>`. Respons sukses: `{ data, meta }`. Error: `{ error: { code, message } }`.'
        },
        tags: [
          { name: 'catalog', description: 'Search, latest, popular, genre, pengumuman' },
          { name: 'series', description: 'Detail seri & daftar chapter' },
          { name: 'chapters', description: 'Gambar chapter' },
          { name: 'system', description: 'Health & status' }
        ]
      }
    })
    await app.register(swaggerUi, { routePrefix: '/docs' })
  }

  const auth = new AuthService(pool, cfg.auth, log)
  const xp = new XpService(pool, cfg.auth.ownerEmails)
  const comments = new CommentService(pool, xp, cfg.auth.ownerEmails)
  const chat = new ChatService(pool, cfg.auth.ownerEmails)
  const reading = new ReadingService(pool)
  const announcements = new AnnouncementRepo(pool)
  const premium = new PremiumService(pool, new SociabuzzGateway({ username: cfg.premium.sociabuzzUsername }), log, cfg.premium.enabled)

  // ---- error handling ----
  app.setErrorHandler((err: any, req, reply) => {
    let status = 500
    let code = 'INTERNAL_ERROR'
    let message = 'Internal server error'
    if (err instanceof AppError) {
      ;({ status, code, message } = err)
    } else if (err?.validation) {
      status = 400
      code = 'BAD_REQUEST'
      message = err.message
    } else if (err?.statusCode && err.statusCode < 500) {
      status = err.statusCode
      code = status === 429 ? 'RATE_LIMITED' : 'BAD_REQUEST'
      message = err.message
    } else {
      const db = dbErrorStatus(err)
      if (db) {
        ;({ status, code, message } = db)
        req.log.warn({ err: err?.message, pg: err?.code }, 'database error')
      } else {
        req.log.error({ err }, 'unhandled error')
      }
    }
    void reply.code(status).send({ error: { code, message } })
  })
  app.setNotFoundHandler((_req, reply) => {
    void reply.code(404).send({ error: { code: 'ROUTE_NOT_FOUND', message: 'Route not found' } })
  })

  // ---- routes ----
  app.get('/', { schema: { hide: true } }, async (_req, reply) => (docs ? reply.redirect('/docs') : reply.send({ name: 'Unified Scraper API' })))
  await app.register(systemRoutes, { store, redis })
  await app.register(
    async v1 => {
      await v1.register(catalogRoutes, { svc })
      await v1.register(seriesRoutes, { svc })
      await v1.register(authRoutes, { auth })
      await v1.register(commentRoutes, { auth, comments })
      await v1.register(chatRoutes, { auth, chat })
      await v1.register(xpRoutes, { auth, xp })
      await v1.register(premiumRoutes, { auth, premium })
      await v1.register(readingRoutes, { auth, reading })
      await v1.register(libraryRoutes, { auth, library: new LibraryService(pool) })
      await v1.register(announcementRoutes, { repo: announcements })
      await v1.register(notificationRoutes, { auth, notifications: new NotificationService(pool) })
      await v1.register(adminRoutes, { auth, store, announcements, svc, push, adminToken: cfg.adminToken, ownerEmails: cfg.auth.ownerEmails })
    },
    { prefix: '/v1' }
  )

  // ---- lifecycle ----
  app.addHook('onReady', async () => {
    if (cfg.serverless) return // tanpa timer latar di serverless
    views.start()
    premium.start() // cek pembayaran yang menggantung di latar belakang (tanpa ini, status hanya diperiksa saat app membukanya)
    if (cfg.warmer.enabled) warmer.start()
  })
  app.addHook('onClose', async () => {
    warmer.stop()
    premium.stop()
    await views.stop()
    await pool.end().catch(() => {})
    redis.disconnect()
  })

  return app
}

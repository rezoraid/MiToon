import type { FastifyPluginAsync } from 'fastify'
import { USER_CONTENT_CSP } from '../core/security.js'
import { MAX_HEADER_BYTES, MAX_HEADER_BLUR } from '../core/premium.js'
import { AppError, NotFoundError, ValidationError } from '../core/errors.js'
import { isJpeg } from '../core/util.js'
import type { AuthService } from '../services/auth.js'
import type { PremiumService, StylePatch } from '../services/premium.js'

const noStore = { 'cache-control': 'no-store' }
const limit = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } })

export const premiumRoutes: FastifyPluginAsync<{ auth: AuthService; premium: PremiumService }> = async (app, { auth, premium }) => {
  // Header profil: JPEG mentah (app sudah memperkecil, maks. ±450 KB). Parser milik plugin ini saja.
  app.addContentTypeParser('image/jpeg', { parseAs: 'buffer', bodyLimit: MAX_HEADER_BYTES }, (_req, body, done) => done(null, body))

  app.get('/premium/plans', { schema: { tags: ['premium'], summary: 'Paket, metode bayar, tema & frame Premium' }, config: limit(60) }, async (_req, reply) => {
    void reply.headers(noStore)
    return { data: premium.catalog() }
  })

  app.post(
    '/premium/orders',
    {
      schema: {
        tags: ['premium'],
        summary: 'Buat pesanan Premium (Sociabuzz). Harga ditetapkan server; pesanan yang sama & masih berlaku dilanjutkan.',
        body: { type: 'object', required: ['plan', 'method'], properties: { plan: { type: 'string', maxLength: 10 }, method: { type: 'string', maxLength: 30 } } }
      },
      config: limit(8)
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      const b = req.body as { plan: string; method: string }
      void reply.headers(noStore)
      return { data: await premium.createOrder(me.id, me.username, b.plan, b.method) }
    }
  )

  app.get('/premium/orders/latest', { schema: { tags: ['premium'], summary: 'Pesanan terbaru yang masih menunggu bayar (atau baru lunas)' }, config: limit(30) }, async (req, reply) => {
    const me = await auth.me(req.headers.authorization)
    const order = await premium.latest(me.id)
    void reply.headers(noStore)
    return { data: { order }, me: order?.status === 'paid' ? await auth.me(req.headers.authorization) : undefined }
  })

  app.get(
    '/premium/orders/:id',
    { schema: { tags: ['premium'], summary: 'Status pesanan; Premium aktif otomatis begitu pembayaran terverifikasi (balasan memuat `me` terbaru saat lunas)', params: { type: 'object', properties: { id: { type: 'string', maxLength: 64 } } } }, config: limit(90) },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      const order = await premium.getOrder(me.id, (req.params as { id: string }).id)
      void reply.headers(noStore)
      return { data: order, me: order.status === 'paid' ? await auth.me(req.headers.authorization) : undefined }
    }
  )

  // ---- tampilan profil Premium ----
  app.patch(
    '/auth/me/style',
    {
      schema: {
        tags: ['premium'],
        summary: 'Tema, frame avatar, blur & posisi background header ("" = bawaan). Mengubah ke nilai baru khusus Premium.',
        body: {
          type: 'object',
          properties: { theme: { type: ['string', 'null'], maxLength: 20 }, frame: { type: ['string', 'null'], maxLength: 20 }, headerBlur: { type: 'integer', minimum: 0, maximum: MAX_HEADER_BLUR }, headerPos: { type: 'integer', minimum: 0, maximum: 100 } }
        }
      },
      config: limit(30)
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      await premium.setStyle(me.id, me.premium, (req.body ?? {}) as StylePatch)
      void reply.headers(noStore)
      return { data: await auth.me(req.headers.authorization) }
    }
  )

  app.put('/auth/me/header', { schema: { tags: ['premium'], summary: 'Ganti background header profil (body = image/jpeg, maks. 450 KB). Khusus Premium.' }, config: limit(10) }, async (req, reply) => {
    const me = await auth.me(req.headers.authorization)
    if (!me.premium) throw new AppError(403, 'PREMIUM_REQUIRED', 'Fitur ini khusus Premium')
    const body = req.body
    if (!isJpeg(body)) throw new ValidationError('Gambar harus berformat JPEG')
    if (body.length > MAX_HEADER_BYTES) throw new ValidationError('Gambar terlalu besar (maks. 450 KB)')
    await premium.setHeader(me.id, body)
    void reply.headers(noStore)
    return { data: await auth.me(req.headers.authorization) }
  })

  app.delete('/auth/me/header', { schema: { tags: ['premium'], summary: 'Hapus background header profil' }, config: limit(10) }, async (req, reply) => {
    const me = await auth.me(req.headers.authorization)
    await premium.clearHeader(me.id)
    void reply.headers(noStore)
    return { data: await auth.me(req.headers.authorization) }
  })

  // Publik: URL memuat ?v=<headerV> sehingga aman di-cache lama.
  app.get(
    '/users/:id/header',
    { schema: { tags: ['premium'], summary: 'Background header profil pengguna', params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } } } }, config: limit(300) },
    async (req, reply) => {
      const buf = await premium.headerOf((req.params as { id: number }).id)
      if (!buf) throw new NotFoundError('Tidak ada background header')
      return reply
        .header('content-type', 'image/jpeg')
        .header('content-security-policy', USER_CONTENT_CSP)
        .header('content-disposition', 'inline; filename="header.jpg"')
        .header('cache-control', 'public, max-age=31536000, immutable')
        .send(buf)
    }
  )
}

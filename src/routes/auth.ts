import type { FastifyPluginAsync } from 'fastify'
import { USER_CONTENT_CSP } from '../core/security.js'
import { MAX_AVATAR_BYTES, isJpeg } from '../core/util.js'
import { MAX_GIF_AVATAR_BYTES, isGif } from '../core/premium.js'
import { AppError, NotFoundError, ValidationError } from '../core/errors.js'
import type { AuthService } from '../services/auth.js'

const noStore = { 'cache-control': 'no-store' }
const str = (max: number) => ({ type: 'string', maxLength: max }) as const
const limit = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } })

export const authRoutes: FastifyPluginAsync<{ auth: AuthService }> = async (app, { auth }) => {
  const reply200 = (reply: { headers: (h: Record<string, string>) => unknown }) => reply.headers(noStore)

  app.get('/auth/config', { schema: { tags: ['auth'], summary: 'Metode login yang aktif (untuk tampilan app)' } }, async (_req, reply) => {
    reply200(reply)
    return { data: { google: auth.googleClientId } }
  })

  app.post(
    '/auth/google',
    { schema: { tags: ['auth'], summary: 'Masuk dengan Google (ID token dari app)', body: { type: 'object', required: ['idToken'], properties: { idToken: str(4096) } } }, config: limit(10) },
    async (req, reply) => {
      reply200(reply)
      return { data: await auth.google((req.body as { idToken: string }).idToken) }
    }
  )

  // ---- foto profil: JPEG mentah (app sudah memotong persegi & mengecilkan ke ±384px) ----
  app.addContentTypeParser('image/jpeg', { parseAs: 'buffer', bodyLimit: MAX_AVATAR_BYTES }, (_req, body, done) => done(null, body))

  // GIF animasi: khusus Premium, maks. 2 MB.
  app.addContentTypeParser('image/gif', { parseAs: 'buffer', bodyLimit: MAX_GIF_AVATAR_BYTES }, (_req, body, done) => done(null, body))

  app.put('/auth/me/avatar', { schema: { tags: ['auth'], summary: 'Ganti foto profil (body = image/jpeg maks. 300 KB, atau image/gif maks. 2 MB khusus Premium)' }, config: limit(10) }, async (req, reply) => {
    const user = await auth.me(req.headers.authorization)
    const body = req.body
    if (isGif(body)) {
      if (!user.premium) throw new AppError(403, 'PREMIUM_REQUIRED', 'Foto profil GIF khusus Premium')
      if (body.length > MAX_GIF_AVATAR_BYTES) throw new ValidationError('GIF terlalu besar (maks. 2 MB)')
      reply200(reply)
      return { data: await auth.setAvatar(user.id, body, 'image/gif') }
    }
    if (!isJpeg(body)) throw new ValidationError('Foto harus berformat JPEG')
    if (body.length > MAX_AVATAR_BYTES) throw new ValidationError('Foto terlalu besar (maks. 300 KB)')
    reply200(reply)
    return { data: await auth.setAvatar(user.id, body) }
  })

  app.post('/auth/logout-all', { schema: { tags: ['auth'], summary: 'Keluar dari semua perangkat (cabut semua token)' }, config: limit(5) }, async (req, reply) => {
    await auth.logoutAll(req.headers.authorization)
    reply200(reply)
    return { data: { ok: true } }
  })

  app.delete('/auth/me/avatar', { schema: { tags: ['auth'], summary: 'Hapus foto profil' }, config: limit(10) }, async (req, reply) => {
    const user = await auth.me(req.headers.authorization)
    reply200(reply)
    return { data: await auth.clearAvatar(user.id) }
  })

  // Publik (dipakai Coil/gambar): URL memuat ?v=<avatarV> sehingga aman di-cache lama.
  app.get(
    '/users/:id/avatar',
    { schema: { tags: ['auth'], summary: 'Foto profil pengguna', params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } } } }, config: limit(300) },
    async (req, reply) => {
      const img = await auth.avatarOf((req.params as { id: number }).id)
      if (!img) throw new NotFoundError('Belum ada foto profil')
      const gif = img.mime === 'image/gif'
      return reply
        .header('content-type', gif ? 'image/gif' : 'image/jpeg')
        .header('content-security-policy', USER_CONTENT_CSP)
        .header('content-disposition', `inline; filename="avatar.${gif ? 'gif' : 'jpg'}"`)
        .header('cache-control', 'public, max-age=31536000, immutable')
        .send(img.data)
    }
  )

  // Publik: profil singkat pengguna lain (nama, level, XP, kapan bergabung, jumlah komentar).
  app.get(
    '/users/:id',
    { schema: { tags: ['auth'], summary: 'Profil publik pengguna', params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } } } }, config: limit(120) },
    async (req, reply) => {
      reply.headers({ 'cache-control': 'public, max-age=30' })
      return { data: await auth.publicProfile((req.params as { id: number }).id) }
    }
  )

  app.patch(
    '/auth/me',
    {
      schema: {
        tags: ['auth'],
        summary: 'Ubah nama tampilan, bio, username (cooldown 7 hari), dan pengaturan privasi (verifiedHidden, readingHidden)',
        body: { type: 'object', properties: { displayName: { type: ['string', 'null'], maxLength: 200 }, username: { type: ['string', 'null'], maxLength: 60 }, bio: { type: ['string', 'null'], maxLength: 600 }, verifiedHidden: { type: 'boolean' }, readingHidden: { type: 'boolean' } } }
      },
      config: limit(10)
    },
    async (req, reply) => {
      const user = await auth.me(req.headers.authorization)
        const b = (req.body ?? {}) as { displayName?: string | null; username?: string | null; bio?: string | null; verifiedHidden?: boolean; readingHidden?: boolean }
      reply200(reply)
      return { data: await auth.updateProfile(user.id, b) }
    }
  )

  app.delete(
    '/auth/me',
    {
      schema: {
        tags: ['auth'],
        summary: 'Hapus akun permanen (wajib `confirm=HAPUS`)',
        querystring: { type: 'object', required: ['confirm'], properties: { confirm: { type: 'string', maxLength: 20 } } }
      },
      config: limit(3)
    },
    async (req, reply) => {
      const user = await auth.me(req.headers.authorization)
      if ((req.query as { confirm: string }).confirm !== 'HAPUS') throw new ValidationError('Ketik HAPUS untuk mengonfirmasi')
      await auth.deleteAccount(user.id)
      reply200(reply)
      return { data: { ok: true } }
    }
  )

  app.get('/auth/me', { schema: { tags: ['auth'], summary: 'Akun yang sedang masuk (Bearer token)' }, config: limit(60) }, async (req, reply) => {
    reply200(reply)
    return { data: await auth.me(req.headers.authorization) }
  })
}

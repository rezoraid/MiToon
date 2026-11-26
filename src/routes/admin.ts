import type { FastifyPluginAsync } from 'fastify'
import type { AuthService } from '../services/auth.js'
import type { PgStore } from '../db/store.js'
import type { AnnouncementRepo } from '../services/announcements.js'
import { NotFoundError, ValidationError } from '../core/errors.js'
import type { ScraperService } from '../services/scraper.js'
import { tokenMatches } from './announcements.js'
import type { PushService } from '../services/push.js'

/** Panel admin: hanya akun owner (email di OWNER_EMAILS). Kontrol penuh data komik & user. */
export const adminRoutes: FastifyPluginAsync<{
  auth: AuthService
  store: PgStore
  announcements: AnnouncementRepo
  svc: ScraperService
  push: PushService
  adminToken?: string
  ownerEmails?: string[]
}> = async (app, { auth, store, announcements, svc, push, adminToken = '', ownerEmails = [] }) => {
  const guard = async (req: { headers: { authorization?: string } }) => {
    await auth.requireOwner(req.headers.authorization)
  }
  // Pengumuman: owner (JWT) atau, bila ADMIN_TOKEN diisi, header x-admin-token (kompatibel dengan curl lama).
  const annGuard = async (req: { headers: { authorization?: string; 'x-admin-token'?: unknown } }) => {
    if (adminToken && tokenMatches(req.headers['x-admin-token'], adminToken)) return
    await auth.requireOwner(req.headers.authorization)
  }
  const strict = { rateLimit: { max: 60, timeWindow: '1 minute' } }

  // ---- overview ----
  app.get('/admin/stats', { onRequest: guard, config: strict, schema: { hide: true } }, async () => {
    return { data: await store.adminStats() }
  })

  // ---- series ----
  app.get<{ Querystring: { q?: string; provider?: string; page?: number; limit?: number } }>(
    '/admin/series',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        querystring: {
          type: 'object',
          properties: {
            q: { type: 'string', maxLength: 200 },
            provider: { type: 'string', maxLength: 40 },
            page: { type: 'integer', minimum: 1, default: 1 },
            limit: { type: 'integer', minimum: 1, maximum: 100, default: 30 }
          }
        }
      }
    },
    async req => {
      const page = req.query.page ?? 1
      const limit = req.query.limit ?? 30
      const data = await store.adminListSeries({
        q: req.query.q?.trim() || undefined,
        provider: req.query.provider?.trim() || undefined,
        limit,
        offset: (page - 1) * limit
      })
      return { data: { ...data, page, limit, hasMore: data.total > page * limit } }
    }
  )

  app.get<{ Params: { id: number } }>(
    '/admin/series/:id',
    {
      onRequest: guard,
      config: strict,
      schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } }
    },
    async req => {
      const row = await store.adminGetSeries(req.params.id)
      if (!row) throw new NotFoundError('Seri tidak ditemukan')
      return { data: row }
    }
  )

  app.patch<{
    Params: { id: number }
    Body: {
      title?: string
      cover?: string
      url?: string
      type?: string | null
      status?: string | null
      synopsis?: string
      author?: string
      artist?: string
      year?: number | null
      rating?: number | null
      chapterCount?: number | null
      latestChapter?: string | null
      genres?: string[]
    }
  }>(
    '/admin/series/:id',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] },
        body: {
          type: 'object',
          additionalProperties: false,
          properties: {
            title: { type: 'string', minLength: 1, maxLength: 500 },
            cover: { type: 'string', maxLength: 2000, pattern: '^(https?://.*)?$' },
            url: { type: 'string', maxLength: 2000, pattern: '^(https?://.*)?$' },
            type: { type: ['string', 'null'], maxLength: 40 },
            status: { type: ['string', 'null'], enum: ['ongoing', 'completed', 'hiatus', null] },
            synopsis: { type: 'string', maxLength: 20000 },
            author: { type: 'string', maxLength: 300 },
            artist: { type: 'string', maxLength: 300 },
            year: { type: ['integer', 'null'] },
            rating: { type: ['number', 'null'] },
            chapterCount: { type: ['integer', 'null'] },
            latestChapter: { type: ['string', 'null'], maxLength: 100 },
            genres: { type: 'array', items: { type: 'string', maxLength: 80 }, maxItems: 30 }
          }
        }
      }
    },
    async req => {
      try {
        const row = await store.adminUpdateSeries(req.params.id, req.body ?? {})
        if (!row) throw new NotFoundError('Seri tidak ditemukan')
        return { data: row }
      } catch (e) {
        if (e instanceof Error && e.message.includes('status harus')) throw new ValidationError(e.message)
        throw e
      }
    }
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/series/:id',
    {
      onRequest: guard,
      config: { rateLimit: { max: 20, timeWindow: '1 minute' } },
      schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } }
    },
    async req => {
      if (!(await store.adminDeleteSeries(req.params.id))) throw new NotFoundError('Seri tidak ditemukan')
      return { data: { deleted: true } }
    }
  )

  // ---- chapters ----
  app.patch<{
    Params: { id: number }
    Body: { title?: string; numberText?: string; number?: number | null }
  }>(
    '/admin/chapters/:id',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] },
        body: {
          type: 'object',
          additionalProperties: false,
          properties: {
            title: { type: 'string', maxLength: 500 },
            numberText: { type: 'string', maxLength: 40 },
            number: { type: ['number', 'null'] }
          }
        }
      }
    },
    async req => {
      if (!(await store.adminUpdateChapter(req.params.id, req.body ?? {}))) throw new NotFoundError('Chapter tidak ditemukan')
      return { data: { updated: true } }
    }
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/chapters/:id',
    {
      onRequest: guard,
      config: { rateLimit: { max: 30, timeWindow: '1 minute' } },
      schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } }
    },
    async req => {
      if (!(await store.adminDeleteChapter(req.params.id))) throw new NotFoundError('Chapter tidak ditemukan')
      return { data: { deleted: true } }
    }
  )

  app.post<{ Params: { id: number } }>(
    '/admin/chapters/:id/fix',
    {
      onRequest: guard,
      config: { rateLimit: { max: 120, timeWindow: '1 minute' } },
      schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } }
    },
    async req => {
      const t = await store.adminChapterTarget(req.params.id)
      if (!t) throw new NotFoundError('Chapter tidak ditemukan')
      return { data: await svc.fixChapter(t.provider, t.ref, t.seriesRef, t.number) }
    }
  )

  // ---- users ----
  app.get<{ Querystring: { q?: string; filter?: string; sort?: string; page?: number; limit?: number } }>(
    '/admin/users',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        querystring: {
          type: 'object',
          properties: {
            q: { type: 'string', maxLength: 200 },
            filter: { type: 'string', enum: ['', 'banned', 'muted', 'verified', 'premium', 'avatar', 'owner'] },
            sort: { type: 'string', enum: ['', 'new', 'xp'] },
            page: { type: 'integer', minimum: 1, default: 1 },
            limit: { type: 'integer', minimum: 1, maximum: 100, default: 30 }
          }
        }
      }
    },
    async req => {
      const page = req.query.page ?? 1
      const limit = req.query.limit ?? 30
      const data = await store.adminListUsers({
        q: req.query.q?.trim() || undefined,
        filter: req.query.filter || undefined,
        sort: req.query.sort || undefined,
        limit,
        offset: (page - 1) * limit,
        ownerEmails
      })
      return { data: { ...data, page, limit, hasMore: data.total > page * limit } }
    }
  )

  app.get<{ Params: { id: number } }>(
    '/admin/users/:id',
    { onRequest: guard, config: strict, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => {
      const row = await store.adminGetUser(req.params.id, ownerEmails)
      if (!row) throw new NotFoundError('User tidak ditemukan')
      return { data: row }
    }
  )

  /** Moderasi: centang verified, Premium Member, blokir/buka blokir, bisukan/buka bisu, reset nama/bio. Balasan = user terbaru. */
  app.patch<{
    Params: { id: number }
    Body: { verified?: boolean; premium?: boolean; banned?: boolean; banReason?: string; muted?: boolean; muteDays?: number; muteReason?: string; clearBio?: boolean; clearName?: boolean }
  }>(
    '/admin/users/:id',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] },
        body: {
          type: 'object',
          additionalProperties: false,
          minProperties: 1,
          properties: {
            verified: { type: 'boolean' },
            premium: { type: 'boolean' },
            banned: { type: 'boolean' },
            banReason: { type: 'string', maxLength: 200 },
            muted: { type: 'boolean' },
            muteDays: { type: 'integer', minimum: 1, maximum: 3650 },
            muteReason: { type: 'string', maxLength: 200 },
            clearBio: { type: 'boolean' },
            clearName: { type: 'boolean' }
          }
        }
      }
    },
    async req => {
      try {
        const row = await store.adminModerateUser(req.params.id, req.body, ownerEmails)
        if (!row) throw new NotFoundError('User tidak ditemukan')
        return { data: row }
      } catch (e) {
        if (e instanceof Error && e.message.startsWith('status:')) throw new ValidationError(e.message.slice(7).trim())
        throw e
      }
    }
  )

  /** Atur level / EXP user: kirim tepat satu dari `xp` (nilai total), `level`, atau `delta` (+/-). Balasan = user terbaru. */
  app.patch<{ Params: { id: number }; Body: { xp?: number; level?: number; delta?: number } }>(
    '/admin/users/:id/xp',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] },
        body: {
          type: 'object',
          additionalProperties: false,
          minProperties: 1,
          properties: {
            xp: { type: 'integer', minimum: 0, maximum: 100000000000 },
            level: { type: 'integer', minimum: 1, maximum: 10000 },
            delta: { type: 'integer', minimum: -100000000000, maximum: 100000000000 }
          }
        }
      }
    },
    async req => {
      try {
        const row = await store.adminSetXp(req.params.id, req.body, ownerEmails)
        if (!row) throw new NotFoundError('User tidak ditemukan')
        return { data: row }
      } catch (e) {
        if (e instanceof Error && e.message.startsWith('status:')) throw new ValidationError(e.message.slice(7).trim())
        throw e
      }
    }
  )

  // ---- global chat ----
  app.delete(
    '/admin/chat',
    { onRequest: guard, config: { rateLimit: { max: 10, timeWindow: '1 minute' } }, schema: { hide: true } },
    async () => ({ data: { deleted: await store.adminClearChat() } })
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/users/:id/chat',
    { onRequest: guard, config: { rateLimit: { max: 20, timeWindow: '1 minute' } }, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => ({ data: { deleted: await store.adminDeleteUserChat(req.params.id) } })
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/users/:id/avatar',
    { onRequest: guard, config: strict, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => {
      const row = await store.adminClearUserAvatar(req.params.id, ownerEmails)
      if (!row) throw new NotFoundError('User tidak ditemukan')
      return { data: row }
    }
  )

  /** Paksa keluar satu user dari semua perangkat (cabut semua token miliknya). */
  app.post<{ Params: { id: number } }>(
    '/admin/users/:id/logout',
    { onRequest: guard, config: { rateLimit: { max: 30, timeWindow: '1 minute' } }, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => {
      if (!(await store.adminRevokeUserSessions(req.params.id))) throw new NotFoundError('User tidak ditemukan')
      return { data: { revoked: true } }
    }
  )

  /** Paksa keluar SEMUA user. Akun admin pemanggil tetap masuk, kecuali `includeSelf: true`. */
  app.post<{ Body: { includeSelf?: boolean } | undefined }>(
    '/admin/logout-all',
    {
      onRequest: guard,
      config: { rateLimit: { max: 5, timeWindow: '1 minute' } },
      schema: { hide: true, body: { type: ['object', 'null'], additionalProperties: false, properties: { includeSelf: { type: 'boolean' } } } }
    },
    async req => {
      const me = await auth.requireOwner(req.headers.authorization)
      const revoked = await store.adminRevokeAllSessions(req.body?.includeSelf ? undefined : me.id)
      return { data: { revoked } }
    }
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/users/:id/comments',
    { onRequest: guard, config: { rateLimit: { max: 20, timeWindow: '1 minute' } }, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => {
      if (!(await store.adminGetUser(req.params.id, ownerEmails))) throw new NotFoundError('User tidak ditemukan')
      return { data: { deleted: await store.adminDeleteUserComments(req.params.id) } }
    }
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/users/:id',
    { onRequest: guard, config: { rateLimit: { max: 10, timeWindow: '1 minute' } }, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => {
      const r = await store.adminDeleteUser(req.params.id, ownerEmails)
      if (r === null) throw new NotFoundError('User tidak ditemukan')
      if (r === false) throw new ValidationError('Akun owner tidak bisa dihapus')
      return { data: { deleted: true } }
    }
  )

  app.get<{ Querystring: { q?: string; page?: number; limit?: number } }>(
    '/admin/comments',
    {
      onRequest: guard,
      config: strict,
      schema: {
        hide: true,
        querystring: {
          type: 'object',
          properties: {
            q: { type: 'string', maxLength: 200 },
            page: { type: 'integer', minimum: 1, default: 1 },
            limit: { type: 'integer', minimum: 1, maximum: 100, default: 30 }
          }
        }
      }
    },
    async req => {
      const page = req.query.page ?? 1
      const limit = req.query.limit ?? 30
      const data = await store.adminListComments({ q: req.query.q?.trim() || undefined, limit, offset: (page - 1) * limit })
      return { data: { ...data, page, limit, hasMore: data.total > page * limit } }
    }
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/comments/:id',
    { onRequest: guard, config: strict, schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } } },
    async req => {
      if (!(await store.adminDeleteComment(req.params.id))) throw new NotFoundError('Komentar tidak ditemukan')
      return { data: { deleted: true } }
    }
  )

  // ---- push notification ----
  app.get('/admin/push', { onRequest: guard, config: strict, schema: { hide: true } }, async () => ({
    data: { configured: push.configured, settings: await store.pushSettings(), log: await store.pushLog(30) }
  }))

  app.put<{ Body: { enabled?: boolean; newChapter?: boolean } }>(
    '/admin/push',
    {
      onRequest: guard,
      config: strict,
      schema: { hide: true, body: { type: 'object', additionalProperties: false, properties: { enabled: { type: 'boolean' }, newChapter: { type: 'boolean' } } } }
    },
    async req => {
      const settings = await store.savePushSettings(req.body)
      push.invalidate()
      return { data: { configured: push.configured, settings, log: await store.pushLog(30) } }
    }
  )

  app.post<{ Body: { title: string; body?: string; seriesId?: string } }>(
    '/admin/push/send',
    {
      onRequest: guard,
      config: { rateLimit: { max: 10, timeWindow: '1 minute' } },
      schema: {
        hide: true,
        body: {
          type: 'object',
          required: ['title'],
          additionalProperties: false,
          properties: {
            title: { type: 'string', minLength: 1, maxLength: 65 },
            body: { type: 'string', maxLength: 240 },
            seriesId: { type: 'string', maxLength: 300 }
          }
        }
      }
    },
    async req => {
      const title = req.body.title.trim()
      if (!title) throw new ValidationError('title tidak boleh kosong')
      const seriesId = req.body.seriesId?.trim() || null
      if (seriesId && !/^[a-z]+:[^\s/\\?#]+$/i.test(seriesId)) throw new ValidationError('seriesId harus berformat "<provider>:<id>"')
      try {
        await push.broadcast({ title, body: (req.body.body ?? '').trim(), seriesId })
      } catch (e) {
        throw new ValidationError((e as Error).message)
      }
      return { data: { sent: true, log: await store.pushLog(30) } }
    }
  )

  // ---- announcements (owner JWT, selain ADMIN_TOKEN) ----
  app.get(
    '/admin/announcements',
    { onRequest: annGuard, config: strict, schema: { hide: true } },
    async () => ({ data: await announcements.active(50) })
  )

  app.post<{ Body: { title: string; body?: string; url?: string; expiresAt?: string } }>(
    '/admin/announcements',
    {
      onRequest: annGuard,
      config: { rateLimit: { max: 20, timeWindow: '1 minute' } },
      schema: {
        hide: true,
        body: {
          type: 'object',
          required: ['title'],
          additionalProperties: false,
          properties: {
            title: { type: 'string', minLength: 1, maxLength: 120 },
            body: { type: 'string', maxLength: 1500 },
            url: { type: 'string', maxLength: 500, pattern: '^https?://' },
            expiresAt: { type: 'string', maxLength: 40 }
          }
        }
      }
    },
    async (req, reply) => {
      const { title, expiresAt } = req.body
      if (!title.trim()) throw new ValidationError('title tidak boleh kosong')
      if (expiresAt !== undefined && Number.isNaN(Date.parse(expiresAt))) throw new ValidationError('expiresAt harus tanggal ISO-8601')
      return reply.code(201).send({ data: await announcements.create(req.body) })
    }
  )

  app.delete<{ Params: { id: number } }>(
    '/admin/announcements/:id',
    {
      onRequest: annGuard,
      config: { rateLimit: { max: 20, timeWindow: '1 minute' } },
      schema: { hide: true, params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } }
    },
    async req => {
      if (!(await announcements.remove(req.params.id))) throw new NotFoundError('Pengumuman tidak ditemukan')
      return { data: { deleted: true } }
    }
  )
}

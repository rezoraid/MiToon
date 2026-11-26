import type { FastifyPluginAsync } from 'fastify'
import type { AuthService } from '../services/auth.js'
import type { NotificationService, ReadKind } from '../services/notifications.js'

const limit = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } })

/** Notifikasi akun (wajib login). Pengumuman admin (tab Informasi) memakai GET /announcements. */
export const notificationRoutes: FastifyPluginAsync<{ auth: AuthService; notifications: NotificationService }> = async (app, { auth, notifications }) => {
  app.get(
    '/notifications',
    {
      schema: { tags: ['auth'], summary: 'Notifikasi: balasan & like pada komentarmu (terbaru dulu, maks. 50)' },
      config: limit(60)
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      void reply.header('cache-control', 'no-store')
      return { data: await notifications.list(me.id) }
    }
  )

  app.get(
    '/notifications/unread',
    {
      schema: { tags: ['auth'], summary: 'Jumlah belum dibaca: `count` (total, untuk pil merah), `notif` (Aktivitas), `info` (Informasi)' },
      config: limit(120)
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      void reply.header('cache-control', 'no-store')
      return { data: await notifications.unreadCount(me.id) }
    }
  )

  app.post(
    '/notifications/read',
    {
      schema: {
        tags: ['auth'],
        summary: 'Tandai notifikasi / pengumuman sudah dibaca',
        description: '`kind=notif` (tab Aktivitas), `kind=info` (tab Informasi) atau `all` (default).',
        querystring: { type: 'object', properties: { kind: { type: 'string', enum: ['all', 'notif', 'info'], default: 'all' } } }
      },
      config: limit(60)
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      await notifications.markRead(me.id, ((req.query as { kind?: ReadKind }).kind ?? 'all'))
      void reply.header('cache-control', 'no-store')
      return { data: { ok: true } }
    }
  )
}

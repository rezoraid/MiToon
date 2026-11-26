import type { FastifyPluginAsync } from 'fastify'
import type { AuthService } from '../services/auth.js'
import type { XpService } from '../services/xp.js'

export const xpRoutes: FastifyPluginAsync<{ auth: AuthService; xp: XpService }> = async (app, { auth, xp }) => {
  app.post(
    '/xp/event',
    {
      schema: {
        tags: ['auth'],
        summary: 'Laporkan aktivitas untuk XP (read = chapter, bookmark = seri, daily = buka app)',
        description: 'Tiap chapter/seri hanya dihitung sekali dan ada batas harian per jenis. Komentar & like dihitung server sendiri. Respons memuat `streak` membaca terbaru; `read` menaikkan streak hari ini walau XP-nya 0.',
        body: { type: 'object', required: ['type'], properties: { type: { type: 'string', maxLength: 20 }, ref: { type: ['string', 'null'], maxLength: 330 } } }
      },
      config: { rateLimit: { max: 60, timeWindow: '1 minute' } }
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      const b = req.body as { type: string; ref?: string | null }
      const gained = await xp.report(me.id, b.type, b.ref)
      void reply.header('cache-control', 'no-store')
      const streak = await xp.streak(me.id)
      return { data: { gained, user: gained ? await auth.me(req.headers.authorization) : me, streak } }
    }
  )

  app.get(
    '/xp/streak',
    {
      schema: {
        tags: ['auth'],
        summary: 'Streak membaca (hari berturut-turut membaca minimal satu chapter, hari WIB)',
        description: '`current` tetap dihitung bila kemarin membaca dan hari ini belum (`readToday` = false); putus bila kemarin pun tidak membaca.'
      },
      config: { rateLimit: { max: 60, timeWindow: '1 minute' } }
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization)
      void reply.header('cache-control', 'private, no-store')
      return { data: await xp.streak(me.id) }
    }
  )

  app.get(
    '/xp/leaderboard',
    {
      schema: {
        tags: ['auth'],
        summary: 'Papan peringkat XP mingguan (Senin 00.00 WIB → Senin berikutnya)',
        description:
          'Diurutkan dari XP terbanyak yang dikumpulkan pada minggu berjalan. Bila request membawa token login, `me` berisi posisi peminta (juga bila di luar `limit`). `resetAt` = kapan papan di-reset.',
        querystring: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 100, default: 50 } } }
      },
      config: { rateLimit: { max: 60, timeWindow: '1 minute' } }
    },
    async (req, reply) => {
      const me = await auth.me(req.headers.authorization).catch(() => null) // login opsional
      const { limit = 50 } = req.query as { limit?: number }
      void reply.header('cache-control', 'private, no-store')
      return { data: await xp.leaderboard(me ? me.id : null, limit) }
    }
  )
}

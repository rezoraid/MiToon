import type { FastifyPluginAsync } from 'fastify'
import type { AuthService } from '../services/auth.js'
import type { ReadingService } from '../services/reading.js'

const limit = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } })

export const readingRoutes: FastifyPluginAsync<{ auth: AuthService; reading: ReadingService }> = async (app, { auth, reading }) => {
  app.put(
    '/reading',
    {
      schema: {
        tags: ['reading'],
        summary: 'Laporkan chapter terakhir yang dibaca sebuah seri (ditimpa) — wajib login',
        description: 'Satu baris per seri. Judul/cover yang tampil di profil diambil dari katalog server; `title` hanya cadangan.',
        body: {
          type: 'object',
          required: ['seriesId', 'chapterId', 'chapterNumber'],
          properties: {
            seriesId: { type: 'string', maxLength: 330 },
            chapterId: { type: 'string', maxLength: 330 },
            chapterNumber: { type: 'string', maxLength: 60 },
            chaptersRead: { type: 'integer', minimum: 0, maximum: 1_000_000 },
            title: { type: 'string', maxLength: 400 }
          }
        }
      },
      config: limit(120)
    },
    async (req, reply) => {
      const user = await auth.me(req.headers.authorization)
      await reading.report(user.id, req.body as Record<string, unknown>)
      void reply.header('cache-control', 'no-store')
      return { data: { ok: true } }
    }
  )

  app.get(
    '/users/:id/reading',
    {
      schema: {
        tags: ['reading'],
        summary: 'Komik yang dibaca seorang pengguna (publik, terbaru dulu)',
        description: 'Tiap item memuat chapter terakhir dan jumlah chapter dibaca. Paginasi: kirim `before` = `readAt` item terakhir yang sudah dimuat.',
        params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } } },
        querystring: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 50, default: 30 }, before: { type: 'integer', minimum: 1 } } }
      },
      config: limit(90)
    },
    async (req, reply) => {
      const q = req.query as { limit?: number; before?: number }
      void reply.header('cache-control', 'no-store')
      return { data: await reading.list((req.params as { id: number }).id, q.limit ?? 30, q.before) }
    }
  )
}

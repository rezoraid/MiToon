import type { FastifyPluginAsync } from 'fastify'
import type { AuthService } from '../services/auth.js'
import type { LibraryService } from '../services/library.js'

export const libraryRoutes: FastifyPluginAsync<{ auth: AuthService; library: LibraryService }> = async (app, { auth, library }) => {
  app.post(
    '/library/sync',
    {
      schema: {
        tags: ['reading'],
        summary: 'Sinkronkan pustaka (bookmark + chapter dibaca) lintas perangkat — wajib login',
        description:
          'Kirim perubahan lokal di `entries` (maks. 200) dan `since` (kursor `now` dari sinkronisasi sebelumnya, 0 untuk semua). Balasan: entri yang berubah sejak `since`. Bila `hasMore`, ulangi dengan `since` = `now` dan `entries` kosong. Penggabungan: bookmark mengikuti perubahan terbaru, chapter dibaca digabung.',
        body: {
          type: 'object',
          properties: {
            since: { type: 'integer', minimum: 0, default: 0 },
            entries: { type: 'array', maxItems: 200, items: { type: 'object' }, default: [] }
          }
        }
      },
      config: { rateLimit: { max: 60, timeWindow: '1 minute' } }
    },
    async (req, reply) => {
      const user = await auth.me(req.headers.authorization)
      const b = req.body as { since?: number; entries?: unknown[] }
      void reply.header('cache-control', 'no-store')
      return { data: await library.sync(user.id, b.since ?? 0, b.entries ?? []) }
    }
  )
}

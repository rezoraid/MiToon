import { timingSafeEqual } from 'node:crypto'
import type { FastifyPluginAsync } from 'fastify'
import type { AnnouncementRepo } from '../services/announcements.js'
import { envelope } from './schemas.js'

const publicCache = (seconds: number) => ({ 'cache-control': `public, max-age=${seconds}` })

/** Perbandingan token waktu-konstan. Token kosong di konfigurasi = admin nonaktif = selalu false. */
export function tokenMatches(given: unknown, expected: string): boolean {
  if (typeof given !== 'string' || !expected) return false
  const a = Buffer.from(given)
  const b = Buffer.from(expected)
  return a.length === b.length && timingSafeEqual(a, b)
}

const item = {
  type: 'object',
  properties: {
    id: { type: 'integer' },
    title: { type: 'string' },
    body: { type: 'string' },
    url: { type: 'string', nullable: true },
    createdAt: { type: 'integer' },
    expiresAt: { type: 'integer', nullable: true }
  }
} as const

export const announcementRoutes: FastifyPluginAsync<{ repo: AnnouncementRepo }> = async (app, { repo }) => {
  app.get(
    '/announcements',
    {
      schema: {
        tags: ['catalog'],
        summary: 'Pengumuman untuk aplikasi',
        description: 'Pengumuman aktif (default 5, maks. 50 lewat `limit`; terbaru dulu). Aplikasi menyembunyikan bagian ini jika kosong.',
        querystring: {
          type: 'object',
          properties: {
            limit: { type: 'integer', minimum: 1, maximum: 50, default: 5 }
          }
        },
        response: { 200: envelope({ type: 'array', items: item }) }
      }
    },
    async (req, reply) => {
      const q = req.query as { limit?: number }
      return reply.headers(publicCache(60)).send({ data: await repo.active(q.limit ?? 5) })
    }
  )
}

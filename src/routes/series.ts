import type { FastifyPluginAsync } from 'fastify'
import type { ScraperService } from '../services/scraper.js'
import { R, idParam } from './schemas.js'

const publicCache = (seconds: number, edge = seconds) => ({
  'cache-control': `public, max-age=${seconds}, s-maxage=${edge}, stale-while-revalidate=${Math.max(edge * 10, 600)}`
})

const NO_STORE = { 'cache-control': 'no-store' }
const wantsRefresh = (q: unknown) => (q as { refresh?: string } | undefined)?.refresh === '1'

export const seriesRoutes: FastifyPluginAsync<{ svc: ScraperService }> = async (app, { svc }) => {
  const seriesId = idParam('Id seri unified, mis. "shinigami:<uuid>", "lumos:<slug>"')

  app.get<{ Params: { id: string } }>(
    '/series/:id',
    {
      schema: {
        tags: ['series'],
        summary: 'Detail seri + daftar chapter',
        description: 'Jika provider asal gagal, otomatis dialihkan ke provider lain yang memiliki judul sama (`meta.fallback = true`).',
        params: seriesId,
        response: R.detail
      }
    },
    async (req, reply) => {
      const refresh = wantsRefresh(req.query)
      return reply.headers(refresh ? NO_STORE : publicCache(300, 60)).send(await svc.detail(req.params.id, refresh))
    }
  )

  app.get<{ Params: { id: string } }>(
    '/series/:id/chapters',
    { schema: { tags: ['series'], summary: 'Daftar chapter (terbaru -> terlama)', params: seriesId, response: R.chapters } },
    async (req, reply) => {
      const refresh = wantsRefresh(req.query)
      return reply.headers(refresh ? NO_STORE : publicCache(300, 60)).send(await svc.chapters(req.params.id, refresh))
    }
  )

  app.get<{ Params: { id: string } }>(
    '/chapters/:id',
    {
      schema: {
        tags: ['chapters'],
        summary: 'Gambar chapter',
        description:
          'Id chapter diambil dari `chapters[].id`. Jika provider gagal, dicarikan chapter bernomor sama di provider lain. ' +
          'Unduh gambar dengan header `imageHeaders`.',
        params: idParam('Id chapter unified, mis. "shinigami:<uuid>", "lumos:<slug>:<chapter-slug>"'),
        response: R.chapter
      }
    },
    async (req, reply) => {
      const refresh = wantsRefresh(req.query)
      return reply.headers(refresh ? NO_STORE : publicCache(900, 3600)).send(await svc.chapter(req.params.id, refresh))
    }
  )
}

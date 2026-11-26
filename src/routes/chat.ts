import type { FastifyPluginAsync } from 'fastify'
import type { AuthService } from '../services/auth.js'
import type { ChatService } from '../services/chat.js'
import { assertNotMuted } from './comments.js'

const limit = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } })

export const chatRoutes: FastifyPluginAsync<{ auth: AuthService; chat: ChatService }> = async (app, { auth, chat }) => {
  app.get(
    '/chat',
    {
      schema: { tags: ['chat'], summary: 'Global Chat: 50 pesan terakhir (lama → baru). Publik — tamu boleh membaca.' },
      // app memanggil tiap ±4 dtk saat tab Chat terbuka (≈15/menit); batas longgar karena banyak pengguna bisa satu IP (NAT seluler)
      config: limit(240)
    },
    async (_req, reply) => {
      void reply.header('cache-control', 'no-store')
      return { data: await chat.list() }
    }
  )

  app.post(
    '/chat',
    {
      schema: { tags: ['chat'], summary: 'Kirim pesan ke Global Chat (1–500 karakter; `replyTo` = id pesan yang dibalas, opsional) — wajib login. Pesan lama di luar 50 terakhir dihapus otomatis.', body: { type: 'object', required: ['body'], properties: { body: { type: 'string', maxLength: 2000 }, replyTo: { type: 'integer', minimum: 1, nullable: true } } } },
      config: limit(40)
    },
    async (req, reply) => {
      const user = await auth.me(req.headers.authorization)
      assertNotMuted(user)
      void reply.header('cache-control', 'no-store')
      const b = req.body as { body: string; replyTo?: number | null }
      return { data: await chat.add(user.id, b.body, b.replyTo) }
    }
  )

  app.delete(
    '/chat/:id',
    { schema: { tags: ['chat'], summary: 'Hapus pesan sendiri (owner: pesan siapa pun)', params: { type: 'object', properties: { id: { type: 'integer', minimum: 1 } } } }, config: limit(30) },
    async (req, reply) => {
      const user = await auth.me(req.headers.authorization)
      await chat.remove(user.id, user.isOwner, (req.params as { id: number }).id)
      void reply.header('cache-control', 'no-store')
      return { data: { ok: true } }
    }
  )
}

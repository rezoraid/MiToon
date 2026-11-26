// Entry serverless Vercel: membangun app Fastify sekali per instance lalu meneruskan request.
// Instance API murni (tanpa swagger) dipisah dari instance dokumentasi -> cold start request aplikasi lebih ringan.
import { createApp } from '../dist/app.js'
import { config } from '../dist/config.js'

const apps = {}
const boot = docs => {
  const key = docs ? 'docs' : 'api'
  apps[key] ??= createApp(config, { docs })
    .then(async app => (await app.ready(), app))
    .catch(err => {
      delete apps[key] // coba lagi pada request berikutnya
      throw err
    })
  return apps[key]
}

export default async function handler(req, res) {
  const url = req.url || '/'
  const docs = url === '/' || url.startsWith('/docs')
  const app = await boot(docs)
  app.server.emit('request', req, res)
}

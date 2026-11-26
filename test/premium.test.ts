import assert from 'node:assert/strict'
import test from 'node:test'
import { PLANS, isGif, isPremiumSql, methodsFor, planSaving, premiumXp } from '../src/core/premium.js'
import { PremiumService, toOrderDto } from '../src/services/premium.js'
import { inputValue, statusFromTitle } from '../src/services/sociabuzz.js'
import { noopLog } from './helpers.js'

test('paket: harga & hemat sesuai (7rb / 18rb hemat 3rb / 60rb hemat 24rb)', () => {
  assert.deepEqual(PLANS.map(p => [p.id, p.price, planSaving(p)]), [['1m', 7000, 0], ['3m', 18000, 3000], ['12m', 60000, 24000]])
  assert.ok(!methodsFor(7000).some(m => m.group === 'bank'), 'VA bank minimal Rp10.000')
  assert.ok(methodsFor(18000).some(m => m.id === 'bca'))
})

test('bonus XP premium dibulatkan ke atas', () => {
  assert.deepEqual([10, 5, 3, 1].map(premiumXp), [15, 8, 5, 2])
  assert.match(isPremiumSql('u'), /u\.premium OR .*u\.premium_until > now\(\)/)
})

test('GIF dikenali dari header, bukan dari nama/tipe kiriman', () => {
  assert.ok(isGif(Buffer.from('GIF89a0123456789')))
  assert.ok(!isGif(Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 6, 7, 8])))
})

test('sociabuzz: csrf dari <input> (urutan atribut bebas) & status dari judul halaman', () => {
  assert.equal(inputValue('<form><input type="hidden" name="sb_token_csrf" value="abc123"></form>', 'sb_token_csrf'), 'abc123')
  assert.equal(inputValue(`<input value='xyz' name='sb_token_csrf'/>`, 'sb_token_csrf'), 'xyz')
  assert.equal(inputValue('<input name="other" value="1">', 'sb_token_csrf'), undefined)
  assert.deepEqual(['Payment Pending', 'Payment Success', 'Expired', 'Page Not Found', 'Payment Failed', 'Home'].map(statusFromTitle), ['pending', 'success', 'expired', 'not_found', 'failed', 'unknown'])
})

/** Pool palsu berisi satu tabel pesanan + satu user; hanya pernyataan yang dipakai PremiumService yang ditiru. */
function fakePool() {
  const orders: any[] = []
  const user = { id: 1, premium_until: null as Date | null }
  const pool = {
    async query(sql: string, p: any[] = []) {
      if (/FROM premium_orders WHERE user_id = \$1 AND plan/.test(sql)) return { rows: orders.filter(o => o.user_id === p[0] && o.plan === p[1] && o.method === p[2] && o.status === 'pending' && o.expires_at > Date.now() + 120_000) }
      if (/count\(\*\)::int AS n FROM premium_orders/.test(sql)) return { rows: [{ n: orders.length }] }
      if (/INSERT INTO premium_orders/.test(sql)) {
        const o = { id: p[0], user_id: p[1], plan: p[2], months: p[3], method: p[4], amount: p[5], total: p[6], status: 'pending', pending_url: p[9], pay_info: JSON.parse(p[10]), created_at: new Date(), expires_at: p[11], paid_at: null, checked_at: null }
        orders.push(o)
        return { rows: [o] }
      }
      if (/SELECT \* FROM premium_orders WHERE id = \$1 AND user_id/.test(sql)) return { rows: orders.filter(o => o.id === p[0] && o.user_id === p[1]) }
      if (/UPDATE premium_orders SET checked_at/.test(sql)) { orders.find(o => o.id === p[0]).checked_at = new Date(); return { rows: [] } }
      if (/WITH o AS \(\s*UPDATE premium_orders SET status = 'paid'/.test(sql)) {
        const o = orders.find(x => x.id === p[0])
        if (o && (o.status === 'pending' || o.status === 'expired')) {
          o.status = 'paid'
          o.paid_at = new Date()
          const base = Math.max(Date.now(), user.premium_until?.getTime() ?? 0)
          user.premium_until = new Date(base + o.months * 30 * 86_400_000)
        }
        return { rows: [] }
      }
      if (/SELECT \* FROM premium_orders WHERE id = \$1$/.test(sql)) return { rows: orders.filter(o => o.id === p[0]) }
      if (/UPDATE premium_orders SET status = \$2/.test(sql)) {
        const o = orders.find(x => x.id === p[0] && x.status === 'pending')
        if (o) o.status = p[1]
        return { rows: o ? [o] : [] }
      }
      throw new Error('SQL tak terduga: ' + sql.slice(0, 80))
    }
  }
  return { pool: pool as any, orders, user }
}

const gateway = (status: () => string) => {
  let created = 0
  return {
    get created() { return created },
    async create(amount: number, o: { method: string }) {
      created++
      return { orderToken: 'tok' + created, invId: 'INV' + created, pendingUrl: 'https://sociabuzz.com/payment/pending?type=qris&inv_id=INV' + created, total: amount + 700, expiresAt: new Date(Date.now() + 30 * 60_000), info: { qr_string: '000201...' } }
    },
    async status() { return { status: status() as any, title: '' } }
  }
}

test('pesanan: harga dari server, dilanjutkan bila sama, VA < Rp10rb ditolak, paket palsu ditolak', async () => {
  const f = fakePool()
  const gw = gateway(() => 'pending')
  const svc = new PremiumService(f.pool, gw, noopLog as any)
  const a = await svc.createOrder(1, 'budi', '3m', 'qris')
  assert.equal(a.amount, 18000)
  assert.equal(a.total, 18700)
  assert.equal(a.fee, 700)
  assert.equal(a.pay.kind, 'qris')
  assert.equal(a.pay.qr, '000201...')
  const b = await svc.createOrder(1, 'budi', '3m', 'qris')
  assert.equal(b.id, a.id, 'pesanan yang sama & masih berlaku dilanjutkan')
  assert.equal(gw.created, 1)
  await assert.rejects(svc.createOrder(1, 'budi', '1m', 'bca'), /minimal/i)
  await assert.rejects(svc.createOrder(1, 'budi', '999', 'qris'), /Paket tidak valid/)
  await assert.rejects(svc.createOrder(1, 'budi', '1m', 'bitcoin'), /Metode/)
})

test('lunas: Premium aktif sekali saja walau dicek berulang; pesanan orang lain tidak terbaca', async () => {
  const f = fakePool()
  let st = 'pending'
  const svc = new PremiumService(f.pool, gateway(() => st), noopLog as any)
  const o = await svc.createOrder(1, 'budi', '1m', 'qris')
  assert.equal((await svc.getOrder(1, o.id)).status, 'pending')
  assert.equal(f.user.premium_until, null)

  st = 'success'
  f.orders[0].checked_at = null // lewati jeda antar-pengecekan
  assert.equal((await svc.getOrder(1, o.id)).status, 'paid')
  const until = f.user.premium_until!.getTime()
  assert.ok(until > Date.now() + 25 * 86_400_000)

  f.orders[0].status = 'pending'; f.orders[0].checked_at = null // simulasi dua pengecek bersamaan: aktivasi kedua tidak boleh menambah lagi
  f.orders[0].status = 'paid'
  assert.equal((await svc.getOrder(1, o.id)).status, 'paid')
  assert.equal(f.user.premium_until!.getTime(), until)

  await assert.rejects(svc.getOrder(2, o.id), /tidak ditemukan/)
})

test('gagal/kedaluwarsa dari Sociabuzz menutup pesanan tanpa mengaktifkan Premium', async () => {
  const f = fakePool()
  let st = 'pending'
  const svc = new PremiumService(f.pool, gateway(() => st), noopLog as any)
  const o = await svc.createOrder(1, 'budi', '1m', 'qris')
  st = 'expired'
  f.orders[0].checked_at = null
  assert.equal((await svc.getOrder(1, o.id)).status, 'expired')
  assert.equal(f.user.premium_until, null)
  assert.equal(toOrderDto(f.orders[0]).paidAt, 0)
})

test('tampilan profil: mengubah khusus Premium, mengosongkan boleh siapa saja, id tak dikenal ditolak', async () => {
  const calls: any[] = []
  const svc = new PremiumService({ async query(_s: string, p: any[]) { calls.push(p); return { rows: [] } } } as any, gateway(() => 'pending'), noopLog as any)
  await assert.rejects(svc.setStyle(1, false, { frame: 'gold' }), /khusus Premium/)
  await assert.rejects(svc.setStyle(1, false, { headerBlur: 5 }), /khusus Premium/)
  await svc.setStyle(1, false, { frame: '', theme: null }) // kosongkan = boleh
  await svc.setStyle(1, true, { frame: 'neon', headerBlur: 12, headerPos: 30 })
  assert.deepEqual(calls.at(-1), [1, false, null, true, 'neon', true, 12, true, 30])
  await assert.rejects(svc.setStyle(1, true, { theme: 'hack' }), /Tema tidak valid/)
  await assert.rejects(svc.setStyle(1, true, { headerBlur: 99 }), /Blur/)
  await assert.rejects(svc.setStyle(1, true, {}), /Tidak ada perubahan/)
})

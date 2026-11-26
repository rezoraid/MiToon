import assert from 'node:assert/strict'
import { test } from 'node:test'
import { CHAT_KEEP, ChatService, cleanChat } from '../src/services/chat.js'

function fakePool() {
  const rows: { id: number; user_id: number; body: string; created_at: Date; reply_to?: number | null; reply_user?: string | null; reply_body?: string | null }[] = []
  const users: Record<number, any> = { 1: { username: 'budi', xp: 100, email: 'own@x.id' }, 2: { username: 'andi', display_name: 'Andi S' } }
  let seq = 0
  let now = 1_700_000_000_000
  const join = (c: any) => ({ id: c.id, body: c.body, created_at: c.created_at, reply_to: c.reply_to, reply_user: c.reply_user, reply_body: c.reply_body, uid: c.user_id, username: users[c.user_id].username, email: users[c.user_id].email,
    display_name: users[c.user_id].display_name, xp: users[c.user_id].xp ?? 0, avatar_v: 0 })
  return {
    rows, tick: (ms: number) => (now += ms),
    async query(sql: string, p: any[] = []) {
      if (sql.includes("interval '10 seconds'")) return { rows: [{ n: rows.filter(r => r.user_id === p[0] && r.created_at.getTime() > now - 10_000).length }] }
      if (sql.startsWith('INSERT INTO chat_messages')) {
        const r = { id: ++seq, user_id: p[0], body: p[1], created_at: new Date(now), reply_to: p[2], reply_user: p[3], reply_body: p[4] }
        rows.push(r)
        return { rows: [{ id: r.id }] }
      }
      if (sql.startsWith('DELETE FROM chat_messages WHERE id <=')) {
        const sorted = [...rows].sort((a, b) => b.id - a.id)
        const cut = sorted[p[0]] // OFFSET n LIMIT 1
        if (cut) for (let i = rows.length - 1; i >= 0; i--) if (rows[i].id <= cut.id) rows.splice(i, 1)
        return { rows: [] }
      }
      if (sql.startsWith('DELETE FROM chat_messages WHERE id = $1')) {
        const i = rows.findIndex(r => r.id === p[0] && (p[1] || r.user_id === p[2]))
        if (i >= 0) rows.splice(i, 1)
        return { rowCount: i >= 0 ? 1 : 0, rows: [] }
      }
      if (sql.includes('WHERE m.id = $1')) return { rows: rows.filter(r => r.id === p[0]).map(join) }
      if (sql.includes('ORDER BY m.id DESC LIMIT $1')) return { rows: [...rows].sort((a, b) => b.id - a.id).slice(0, p[0]).map(join) }
      throw new Error(`sql tak dikenal: ${sql}`)
    }
  }
}

test('pesan chat: dirapikan, kosong/terlalu panjang ditolak', () => {
  assert.equal(cleanChat('  halo\u0000 \r\n\r\n\r\n\r\ndunia '), 'halo \n\ndunia')
  assert.throws(() => cleanChat('  '), /kosong/)
  assert.throws(() => cleanChat('x'.repeat(501)), /terlalu panjang/)
  assert.equal(cleanChat('x'.repeat(500)).length, 500)
})

test('chat: tabel dipangkas ke 50 pesan terakhir, daftar urut lama → baru', async () => {
  const pool = fakePool()
  const svc = new ChatService(pool as any, ['own@x.id'])
  for (let i = 1; i <= 60; i++) {
    pool.tick(11_000) // di luar jendela burst 10 dtk
    await svc.add(i % 2 ? 1 : 2, `pesan ${i}`)
    assert.ok(pool.rows.length <= CHAT_KEEP)
  }
  assert.equal(pool.rows.length, CHAT_KEEP)
  const list = await svc.list()
  assert.equal(list.length, CHAT_KEEP)
  assert.equal(list[0].body, 'pesan 11')
  assert.equal(list.at(-1)!.body, 'pesan 60')
  assert.equal(list[0].user.username, 'budi')
  assert.equal(list[0].user.isOwner, true)
  assert.equal(list[1].user.displayName, 'Andi S')
})

test('chat: batas kirim 5 pesan / 10 detik per akun', async () => {
  const pool = fakePool()
  const svc = new ChatService(pool as any)
  for (let i = 0; i < 5; i++) await svc.add(2, `x${i}`)
  await assert.rejects(svc.add(2, 'terlalu banyak'), /Terlalu cepat/)
  await svc.add(1, 'akun lain tidak terpengaruh')
})

test('chat: hapus hanya milik sendiri, owner boleh hapus semua', async () => {
  const pool = fakePool()
  const svc = new ChatService(pool as any)
  const a = await svc.add(1, 'punya budi')
  await assert.rejects(svc.remove(2, false, a.id), /tidak ditemukan/)
  await svc.remove(2, true, a.id) // owner
  await assert.rejects(svc.remove(1, false, a.id), /tidak ditemukan/) // sudah hilang
})

test('chat: balasan menyimpan kutipan, tetap tampil walau pesan asli dihapus; balas pesan yang tak ada ditolak', async () => {
  const pool = fakePool()
  const svc = new ChatService(pool as any)
  const a = await svc.add(2, 'halo\n\nsemua   apa kabar')
  assert.equal(a.replyTo, null)
  const b = await svc.add(1, 'baik', a.id)
  assert.deepEqual(b.replyTo, { id: a.id, userName: 'Andi S', body: 'halo semua apa kabar' })
  await svc.remove(2, false, a.id)
  const list = await svc.list()
  assert.equal(list[0].replyTo?.body, 'halo semua apa kabar')
  await assert.rejects(svc.add(1, 'balas hantu', 9999), /sudah tidak ada/)
})

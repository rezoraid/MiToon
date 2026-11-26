import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkTarget, cleanComment, isJpeg } from '../src/core/util.js'
import { CommentService } from '../src/services/comments.js'
import { XP_RULES, levelCost, levelFor, xpForLevel, XP_MAX, MAX_LEVEL, streakOf, weekWindow } from '../src/core/xp.js'
import { premiumXp } from '../src/core/premium.js'
import { XpService } from '../src/services/xp.js'

test('target komentar: seri & chapter valid, lainnya ditolak', () => {
  assert.equal(checkTarget('s:lumos:abc-123'), 's:lumos:abc-123')
  assert.equal(checkTarget('c:lumos:12:345'), 'c:lumos:12:345')
  for (const bad of ['', 'x:lumos:a', 's:lumos', 's:lumos:a b', 's:lumos:' + 'a'.repeat(400), undefined, 5]) assert.throws(() => checkTarget(bad), /tidak valid/)
})

test('isi komentar: dirapikan, kosong/terlalu panjang ditolak', () => {
  assert.equal(cleanComment('  halo\u0000 dunia\r\n\r\n\r\n\r\nlagi  '), 'halo dunia\n\nlagi')
  assert.throws(() => cleanComment('   '), /kosong/)
  assert.throws(() => cleanComment('x'.repeat(1001)), /terlalu panjang/)
  assert.equal(cleanComment('x'.repeat(1000)).length, 1000)
})

test('isJpeg memeriksa magic bytes', () => {
  assert.ok(isJpeg(Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 1])))
  assert.ok(!isJpeg(Buffer.from('<html>')))
  assert.ok(!isJpeg('bukan buffer'))
})

function fakePool() {
  const rows: any[] = []
  const votes: { comment_id: number; user_id: number; value: number }[] = []
  const users: Record<number, { username: string; display_name?: string; xp?: number }> = { 1: { username: 'budi', xp: 100 }, 2: { username: 'andi', display_name: 'Andi S' } }
  let seq = 0
  const join = (c: any, viewer: number | null = null) => ({
    id: c.id, body: c.body, created_at: c.created_at, parent_id: c.parent_id ?? null, uid: c.user_id,
    username: users[c.user_id].username, display_name: users[c.user_id].display_name, xp: users[c.user_id].xp ?? 0, avatar_v: 0,
    reply_to_name: c.reply_to_user_id ? (users[c.reply_to_user_id].display_name || users[c.reply_to_user_id].username) : null,
    likes: votes.filter(v => v.comment_id === c.id && v.value === 1).length,
    dislikes: votes.filter(v => v.comment_id === c.id && v.value === -1).length,
    my_vote: votes.find(v => v.comment_id === c.id && v.user_id === viewer)?.value ?? 0,
    reply_count: rows.filter(r => r.parent_id === c.id).length
  })
  return {
    rows, votes,
    async query(sql: string, p: any[] = []) {
      if (sql.includes("interval '60 seconds'")) return { rows: [{ n: 0 }] }
      if (sql.startsWith('INSERT INTO comments')) {
        const c = { id: ++seq, target: p[0], user_id: p[1], body: p[2], parent_id: p[3], reply_to_user_id: p[4], created_at: new Date(1_700_000_000_000 + seq) }
        rows.push(c)
        return { rows: [{ id: c.id }] }
      }
      if (sql.startsWith('SELECT id, target, parent_id, user_id FROM comments')) return { rows: rows.filter(c => c.id === p[0]) }
      if (sql.startsWith('SELECT user_id FROM comments')) return { rows: rows.filter(c => c.id === p[0]) }
      if (sql.includes('WHERE c.user_id = $1')) {
        const out = rows.filter(c => c.user_id === p[0] && (p[1] == null || c.id < p[1])).sort((a, b) => b.id - a.id).slice(0, p[2])
        return { rows: out.map(c => ({ ...join(c, p[3]), target: c.target })) }
      }
      if (sql.includes('WHERE c.id = $1')) return { rows: rows.filter(c => c.id === p[0]).map(c => join(c, p[1])) }
      if (sql.includes('WHERE c.target = $1 AND')) {
        const out = rows.filter(c => c.target === p[0] && c.parent_id == null && (p[1] == null || c.id < p[1])).sort((a, b) => b.id - a.id).slice(0, p[2])
        return { rows: out.map(c => join(c, p[3])) }
      }
      if (sql.includes('WHERE c.parent_id = $1')) {
        const out = rows.filter(c => c.parent_id === p[0] && c.id > p[1]).sort((a, b) => a.id - b.id).slice(0, p[2])
        return { rows: out.map(c => join(c, p[3])) }
      }
      if (sql.startsWith('SELECT count(*)::int AS n FROM comments WHERE parent_id')) return { rows: [{ n: rows.filter(c => c.parent_id === p[0]).length }] }
      if (sql.startsWith('SELECT count(*)::int AS n FROM comments WHERE user_id')) return { rows: [{ n: rows.filter(c => c.user_id === p[0]).length }] }
      if (sql.startsWith('SELECT count(*)::int AS n FROM comments WHERE target')) return { rows: [{ n: rows.filter(c => c.target === p[0]).length }] }
      if (sql.startsWith('DELETE FROM comment_votes')) {
        const i = votes.findIndex(v => v.comment_id === p[0] && v.user_id === p[1])
        if (i >= 0) votes.splice(i, 1)
        return { rows: [] }
      }
      if (sql.startsWith('INSERT INTO comment_votes')) {
        const v = votes.find(x => x.comment_id === p[0] && x.user_id === p[1])
        if (v) v.value = p[2]
        else votes.push({ comment_id: p[0], user_id: p[1], value: p[2] })
        return { rows: [] }
      }
      if (sql.startsWith('SELECT count(*) FILTER')) {
        const vs = votes.filter(v => v.comment_id === p[0])
        return { rows: [{ likes: vs.filter(v => v.value === 1).length, dislikes: vs.filter(v => v.value === -1).length }] }
      }
      if (sql.startsWith('DELETE FROM comments')) {
        const i = rows.findIndex(c => c.id === p[0] && c.user_id === p[1])
        if (i >= 0) {
          const id = rows[i].id
          rows.splice(i, 1)
          for (let j = rows.length - 1; j >= 0; j--) if (rows[j].parent_id === id) rows.splice(j, 1) // ON DELETE CASCADE
        }
        return { rowCount: i >= 0 ? 1 : 0, rows: [] }
      }
      throw new Error(`sql tak dikenal: ${sql}`)
    }
  }
}

test('komentar: tambah, daftar terbaru dulu + paginasi, hapus hanya milik sendiri', async () => {
  const svc = new CommentService(fakePool() as any)
  const t = 's:lumos:seri-1'
  const a = await svc.add(1, t, 'pertama')
  await svc.add(2, t, 'kedua')
  await svc.add(1, t, 'ketiga')
  await svc.add(1, 'c:lumos:bab-9', 'beda target')
  assert.equal(a.user.username, 'budi')
  assert.equal(a.user.level, levelFor(100).level)

  const page1 = await svc.list(t, 2)
  assert.equal(page1.total, 3)
  assert.equal(page1.hasMore, true)
  assert.deepEqual(page1.items.map(i => i.body), ['ketiga', 'kedua'])
  const page2 = await svc.list(t, 2, page1.items.at(-1)!.id)
  assert.equal(page2.hasMore, false)
  assert.deepEqual(page2.items.map(i => i.body), ['pertama'])

  await assert.rejects(svc.remove(2, a.id), /tidak ditemukan/) // bukan penulisnya
  await svc.remove(1, a.id)
})

test('balasan: menempel ke komentar teratas, "membalas" hanya untuk balasan, daftar teratas tak memuat balasan', async () => {
  const svc = new CommentService(fakePool() as any)
  const t = 's:lumos:seri-1'
  const top = await svc.add(1, t, 'komentar')
  const r1 = await svc.add(2, t, 'balasan langsung', top.id)
  assert.equal(r1.parentId, top.id)
  assert.equal(r1.replyTo, '') // membalas komentar teratas: tanpa label
  const r2 = await svc.add(1, t, 'balas si andi', r1.id)
  assert.equal(r2.parentId, top.id) // tetap 1 tingkat
  assert.equal(r2.replyTo, 'Andi S')

  await assert.rejects(svc.add(1, 's:lumos:lain', 'x', top.id), /halaman yang sama/)
  await assert.rejects(svc.add(1, t, 'x', 999), /tidak ditemukan/)

  const list = await svc.list(t, 20)
  assert.equal(list.total, 3)
  assert.deepEqual(list.items.map(i => [i.body, i.replyCount]), [['komentar', 2]])
  const rep = await svc.replies(top.id, 1)
  assert.deepEqual(rep.items.map(i => i.body), ['balasan langsung'])
  assert.equal(rep.hasMore, true)
  const rep2 = await svc.replies(top.id, 1, rep.items[0].id)
  assert.deepEqual(rep2.items.map(i => i.body), ['balas si andi'])
  assert.equal(rep2.hasMore, false)
})

test('riwayat komentar: hanya milik sendiri (termasuk balasan), terbaru dulu, memuat target, paginasi', async () => {
  const svc = new CommentService(fakePool() as any)
  const top = await svc.add(2, 's:lumos:seri-1', 'milik andi')
  await svc.add(1, 's:lumos:seri-1', 'satu')
  await svc.add(1, 'c:lumos:bab-9', 'dua')
  await svc.add(1, 's:lumos:seri-1', 'balasan tiga', top.id)

  const p1 = await svc.mine(1, 2)
  assert.equal(p1.total, 3)
  assert.equal(p1.hasMore, true)
  assert.deepEqual(p1.items.map(i => [i.body, i.target, i.parentId === null]), [['balasan tiga', 's:lumos:seri-1', false], ['dua', 'c:lumos:bab-9', true]])
  const p2 = await svc.mine(1, 2, p1.items.at(-1)!.id)
  assert.equal(p2.hasMore, false)
  assert.deepEqual(p2.items.map(i => i.body), ['satu'])
  assert.deepEqual((await svc.mine(99)).items, [])
  // profil orang lain: myVote mengikuti pemirsa, bukan pemilik komentar
  await svc.vote(2, p1.items[0].id, 1)
  assert.equal((await svc.mine(1, 1, undefined, 2)).items[0].myVote, 1)
  assert.equal((await svc.mine(1, 1, undefined, null)).items[0].myVote, 0)
})

test('like/dislike: satu suara per pengguna, bisa ganti & batal, myVote sesuai pemirsa', async () => {
  const pool = fakePool()
  const svc = new CommentService(pool as any)
  const c = await svc.add(1, 's:lumos:seri-1', 'halo')
  assert.deepEqual(await svc.vote(2, c.id, 1), { likes: 1, dislikes: 0, myVote: 1 })
  assert.deepEqual(await svc.vote(2, c.id, 1), { likes: 1, dislikes: 0, myVote: 1 }) // tidak ganda
  assert.deepEqual(await svc.vote(1, c.id, -1), { likes: 1, dislikes: 1, myVote: -1 })
  assert.deepEqual(await svc.vote(2, c.id, -1), { likes: 0, dislikes: 2, myVote: -1 }) // ganti suara
  assert.deepEqual(await svc.vote(2, c.id, 0), { likes: 0, dislikes: 1, myVote: 0 }) // batal
  await svc.vote(2, c.id, 1)
  const asAndi = (await svc.list('s:lumos:seri-1', 20, undefined, 2)).items[0]
  const asAnon = (await svc.list('s:lumos:seri-1', 20)).items[0]
  assert.deepEqual([asAndi.likes, asAndi.dislikes, asAndi.myVote], [1, 1, 1])
  assert.equal(asAnon.myVote, 0)
  await assert.rejects(svc.vote(2, c.id, 5), /Suara/)
  await assert.rejects(svc.vote(2, 999, 1), /tidak ditemukan/)
})

test('XP dari like: hanya untuk like orang lain, sekali per penyuka', async () => {
  const awards: any[] = []
  const xp = { award: async (...a: any[]) => (awards.push(a), 1) } as any
  const svc = new CommentService(fakePool() as any, xp)
  const c = await svc.add(1, 's:lumos:seri-1', 'halo') // komentar -> XP komentar untuk penulis
  await svc.vote(2, c.id, 1)
  await svc.vote(1, c.id, 1) // like komentar sendiri: tanpa XP
  await svc.vote(2, c.id, -1) // dislike: tanpa XP
  assert.deepEqual(awards, [[1, 'comment', String(c.id)], [1, 'liked', `${c.id}:2`]])
})

test('level 9999 & 10000 bisa dicapai (bug admin tersangkut di Lv. 663)', () => {
  for (const l of [663, 664, 5000, 9999, MAX_LEVEL]) assert.equal(levelFor(xpForLevel(l)).level, l)
  assert.equal(levelFor(xpForLevel(9999) - 1).level, 9998)
  assert.equal(levelFor(XP_MAX * 10).level, MAX_LEVEL)
  assert.ok(XP_MAX < 100_000_000_000)
})

test('kurva level: awal murah, makin tinggi makin mahal, konsisten dengan levelFor', () => {
  assert.equal(levelCost(1), 20)
  for (let l = 1; l < 200; l++) assert.ok(levelCost(l + 1) > levelCost(l))
  assert.deepEqual(levelFor(0), { level: 1, into: 0, need: 20 })
  assert.deepEqual(levelFor(19), { level: 1, into: 19, need: 20 })
  assert.deepEqual(levelFor(20), { level: 2, into: 0, need: levelCost(2) })
  let total = 0
  for (let l = 1; l < 10; l++) total += levelCost(l)
  assert.equal(levelFor(total).level, 10)
  assert.equal(levelFor(total - 1).level, 9)
  assert.equal(levelFor(-5).level, 1)
  // aktivitas ringan sudah cukup untuk naik cepat di awal: 4 chapter (40 XP) = level 2; level 10 hanya 384 XP
  assert.equal(levelFor(4 * XP_RULES.read.xp).level, 2)
})

test('XpService: ledger (sekali per ref + batas harian) dan validasi laporan dari app', async () => {
  const calls: any[] = []
  let granted = true
  const svc = new XpService({ query: async (_sql: string, p: any[]) => (calls.push(p), { rows: granted ? [{ id: p[0] }] : [] }) } as any)
  assert.equal(await svc.report(7, 'read', 'lumos:bab-1'), XP_RULES.read.xp)
  assert.deepEqual(calls[0], [7]) // hari baca dicatat dulu (untuk streak)
  assert.deepEqual(calls[1].slice(0, 3), [7, 'read', 'lumos:bab-1'])
  assert.deepEqual(calls[1].slice(3), [XP_RULES.read.xp, XP_RULES.read.perDay, premiumXp(XP_RULES.read.xp)]) // parameter terakhir = XP untuk Premium aktif
  const prem = new XpService({ query: async () => ({ rows: [{ gained: premiumXp(XP_RULES.read.xp) }] }) } as any)
  assert.equal(await prem.award(7, 'read', 'lumos:bab-2'), 15, 'XP yang dikembalikan = yang benar-benar dicatat server (Premium lebih besar)')
  granted = false
  const before = calls.length
  assert.equal(await svc.report(7, 'read', 'lumos:bab-1'), 0) // sudah pernah / kuota habis
  assert.equal(calls.length - before, 2, 'streak tetap dicatat walau XP 0')
  granted = true
  await svc.report(7, 'daily', undefined)
  assert.match(calls.at(-1)[2], /^\d{4}-\d{2}-\d{2}$/) // ref = tanggal WIB
  await assert.rejects(svc.report(7, 'comment', 'x:y'), /tidak valid/) // jenis server-only ditolak
  await assert.rejects(svc.report(7, 'read', 'tanpa titik dua'), /Referensi/)
  await assert.rejects(svc.report(7, 'bookmark', undefined), /Referensi/)
})

test('jendela mingguan: Senin 00.00 WIB sampai Senin berikutnya', () => {
  // Rabu 2026-11-18 10:00 WIB -> minggu mulai Senin 2026-11-16 00:00 WIB (= 15 Nov 17:00 UTC)
  const w = weekWindow(Date.parse('2026-11-18T03:00:00Z'))
  assert.equal(w.startDay, '2026-11-16')
  assert.equal(new Date(w.startAt).toISOString(), '2026-11-15T17:00:00.000Z')
  assert.equal(new Date(w.endAt).toISOString(), '2026-11-22T17:00:00.000Z')
  // Senin 00.00 WIB tepat = awal minggu baru; semenit sebelumnya masih minggu lalu
  assert.equal(weekWindow(Date.parse('2026-11-15T17:00:00Z')).startDay, '2026-11-16')
  assert.equal(weekWindow(Date.parse('2026-11-15T16:59:00Z')).startDay, '2026-11-09')
  // Minggu malam 23.59 WIB masih minggu yang sama (Senin 16 Nov)
  assert.equal(weekWindow(Date.parse('2026-11-22T16:59:00Z')).startDay, '2026-11-16')
})

test('leaderboard: urutan, tanpa email bocor, posisi sendiri di luar limit', async () => {
  const calls: { sql: string; params: unknown[] }[] = []
  const row = (rank: number, id: number, wxp: number, extra: Record<string, unknown> = {}) =>
    ({ rank, user_id: id, wxp, username: 'u' + id, display_name: null, xp: 40, email: `u${id}@x.id`, verified: false, premium: false, verified_hidden: false, avatar_v: 0, total: 120, ...extra })
  const pool = {
    async query(sql: string, params: unknown[]) {
      calls.push({ sql, params })
      return { rows: [row(1, 7, 90, { email: 'Owner@X.id', verified_hidden: true }), row(2, 3, 80, { premium: true }), row(77, 9, 5)] }
    }
  }
  const board = await new XpService(pool as never, ['owner@x.id']).leaderboard(9, 2, Date.parse('2026-11-18T03:00:00Z'))
  assert.deepEqual(calls[0].params, ['2026-11-16', 2, 9])
  assert.match(calls[0].sql, /banned_at IS NULL/)
  assert.equal(board.total, 120)
  assert.deepEqual(board.items.map(i => [i.rank, i.id, i.weekXp]), [[1, 7, 90], [2, 3, 80]]) // baris ke-77 hanya untuk `me`
  assert.deepEqual(board.me, { rank: 77, weekXp: 5 })
  assert.ok(board.items[0].isOwner && !board.items[0].verified) // owner tapi centang disembunyikan
  assert.ok(board.items[1].premium)
  assert.ok(!JSON.stringify(board).includes('@'))
  const anon = await new XpService(pool as never).leaderboard(null, 2)
  assert.equal(anon.me, null)
  assert.equal((await new XpService({ query: async () => ({ rows: [] }) } as never).leaderboard(1)).total, 0)
})


test('streakOf: berturut-turut, masih hidup bila baru kemarin, putus bila lebih lama', () => {
  const now = Date.parse('2026-11-25T02:00:00Z') // 25 Nov 09.00 WIB
  assert.deepEqual(streakOf([], now), { current: 0, readToday: false })
  assert.deepEqual(streakOf(['2026-11-25', '2026-11-24', '2026-11-23'], now), { current: 3, readToday: true })
  // belum baca hari ini, tapi kemarin & sebelumnya baca -> streak masih hidup
  assert.deepEqual(streakOf(['2026-11-24', '2026-11-23'], now), { current: 2, readToday: false })
  // lompat satu hari -> hanya runtutan terakhir
  assert.deepEqual(streakOf(['2026-11-25', '2026-11-23', '2026-11-22'], now), { current: 1, readToday: true })
  // terakhir baca 2 hari lalu -> putus
  assert.deepEqual(streakOf(['2026-11-23', '2026-11-22'], now), { current: 0, readToday: false })
  // duplikat & urutan acak tidak masalah
  assert.deepEqual(streakOf(['2026-11-23', '2026-11-25', '2026-11-24', '2026-11-24'], now), { current: 3, readToday: true })
  // hari WIB: 23.30 UTC tanggal 24 sudah 06.30 WIB tanggal 25
  assert.deepEqual(streakOf(['2026-11-24'], Date.parse('2026-11-24T23:30:00Z')), { current: 1, readToday: false })
  assert.deepEqual(streakOf(['2026-11-25'], Date.parse('2026-11-24T23:30:00Z')), { current: 1, readToday: true })
})

test('XpService.streak membaca read_days milik pengguna', async () => {
  let args: any[] = []
  const svc = new XpService({ query: async (_s: string, p: any[]) => (args = p, { rows: [{ d: '2026-11-24' }, { d: '2026-11-23' }] }) } as any)
  assert.deepEqual(await svc.streak(9, Date.parse('2026-11-25T02:00:00Z')), { current: 2, readToday: false })
  assert.deepEqual(args, [9])
})

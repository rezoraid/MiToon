import assert from 'node:assert/strict'
import { test } from 'node:test'
import { formatCooldown, signToken, slugUsername, verifyToken } from '../src/core/auth.js'
import { AuthService } from '../src/services/auth.js'

test('token: valid, kedaluwarsa, dirusak, secret lain', () => {
  const now = 1_700_000_000_000
  const t = signToken(7, 's3cret', 60, now)
  assert.deepEqual(verifyToken(t, 's3cret', now + 1000), { sub: 7, tv: 0 })
  assert.equal(verifyToken(t, 's3cret', now + 61_000), null)
  assert.equal(verifyToken(t, 'lain', now), null)
  const [h, , s] = t.split('.')
  const forged = `${h}.${Buffer.from(JSON.stringify({ sub: 1, exp: 9_999_999_999 })).toString('base64url')}.${s}`
  assert.equal(verifyToken(forged, 's3cret', now), null)
  assert.equal(verifyToken('a.b', 's3cret', now), null)
})

test('slug username dari email', () => {
  assert.equal(slugUsername('Budi Santoso!'), 'BudiSantoso')
  assert.ok(slugUsername('!!').length >= 3)
})

// ---- service dengan pool tiruan ----
const DAY = 86_400_000
const clock = { now: 1_700_000_000_000 }
const cooldown = (u: any) => (u.changedAt ? Math.max(0, Math.ceil((u.changedAt + 7 * DAY - clock.now) / 1000)) : 0)
function fakes() {
  const users: any[] = []
  const pool = {
    async query(sql: string, p: any[] = []) {
      if (sql.startsWith('INSERT INTO users (username, email, google_sub)')) {
        if (users.some(u => u.username.toLowerCase() === p[0].toLowerCase())) throw Object.assign(new Error('dup'), { code: '23505', constraint: 'users_username_lower' })
        const u = { id: users.length + 1, username: p[0], email: p[1], google_sub: p[2] }
        users.push(u)
        return { rows: [u] }
      }
      if (sql.includes('WHERE google_sub = $1')) return { rows: users.filter(u => u.google_sub === p[0]).slice(0, 1) }
      if (sql.startsWith('UPDATE users SET google_sub = $1')) {
        const u = users.find(x => x.email?.toLowerCase() === String(p[1]).toLowerCase() && !x.google_sub)
        if (!u) return { rows: [] }
        u.google_sub = p[0]
        return { rows: [u] }
      }
      if (sql.startsWith('UPDATE users SET verified = true')) {
        const u = users.find(x => x.id === p[0])
        if (u) u.verified = true
        return { rows: u ? [u] : [] }
      }
      if (sql.startsWith('UPDATE users SET\n           display_name')) {
        const [id, setName, name, setUser, username, setBio, bio] = p
        const u = users.find(x => x.id === id)
        if (!u) return { rows: [] }
        const cd = cooldown(u)
        if (setUser && cd > 0) return { rows: [] }
        if (setUser && users.some(o => o.id !== id && o.username.toLowerCase() === String(username).toLowerCase())) throw Object.assign(new Error('dup'), { code: '23505', constraint: 'users_username_lower' })
        if (setName) u.displayName = name
        if (setBio) u.bio = bio
        if (setUser) { u.username = username; u.changedAt = clock.now }
        if (p[9]) u.readingHidden = p[10]
        return { rows: [{ ...u, usernameCooldownSec: cooldown(u) }] }
      }
      if (sql.includes('FROM users u WHERE u.id = $1')) return { rows: users.filter(u => u.id === p[0]).map(u => ({ ...u, createdAt: new Date(1_700_000_000_000), commentCount: 3 })) }
      if (sql.startsWith('DELETE FROM users WHERE id = $1')) {
        const i = users.findIndex(u => u.id === p[0])
        if (i >= 0) users.splice(i, 1)
        return { rowCount: i >= 0 ? 1 : 0, rows: [] }
      }
      if (sql.includes('WHERE id = $1')) return { rows: users.filter(u => u.id === p[0]).map(u => ({ ...u, usernameCooldownSec: cooldown(u) })) }
      throw new Error(`sql tak dikenal: ${sql}`)
    }
  }
  return { users, pool }
}
const cfg = { jwtSecret: 'x', tokenTtlSec: 3600, googleClientId: '', ownerEmails: [] as string[] }
const log = { warn() {}, error() {} }
const fakeGoogle = (async (url: string) => {
  const email = decodeURIComponent(String(url).split('id_token=')[1])
  if (!email.includes('@')) return { ok: false, json: async () => ({}) }
  return { ok: true, json: async () => ({ iss: 'accounts.google.com', aud: 'cid', exp: String(Math.floor(Date.now() / 1000) + 600), email_verified: 'true', sub: `sub-${email}`, email }) }
}) as any
const make = (pool: any, c: any = cfg) => new AuthService(pool, { ...c, googleClientId: 'cid' }, log, fakeGoogle)

test('login Google: buat akun, masuk lagi = akun sama, me()', async () => {
  const f = fakes()
  const svc = make(f.pool)
  const r = await svc.google('budi@mail.com')
  assert.equal(r.user.username, 'budi')
  assert.equal(f.users.length, 1)
  const l = await svc.google('budi@mail.com')
  assert.equal(l.user.id, r.user.id)
  assert.equal(f.users.length, 1)
  assert.equal((await svc.me(`Bearer ${l.token}`)).username, 'budi')
  await assert.rejects(svc.me('Bearer palsu'), /Sesi tidak valid/)
  await assert.rejects(svc.google('bukan-email'), /Token Google tidak valid/)
})

test('login Google: email owner otomatis verified', async () => {
  const f = fakes()
  const svc = make(f.pool, { ...cfg, ownerEmails: ['owner@x.id'] })
  const r = await svc.google('owner@x.id')
  assert.equal(r.user.isOwner, true)
  assert.equal(r.user.verified, true)
  assert.equal((await svc.google('lain@x.id')).user.verified, false)
})

test('login Google tidak aktif bila tidak dikonfigurasi', async () => {
  const f = fakes()
  const svc = new AuthService(f.pool as any, { ...cfg, googleClientId: '' }, log)
  await assert.rejects(svc.google('abc'), /belum dikonfigurasi/)
})

test('formatCooldown: hari/jam/menit', () => {
  assert.equal(formatCooldown(6 * 86400 + 3 * 3600), '6 hari 3 jam')
  assert.equal(formatCooldown(2 * 86400), '2 hari')
  assert.equal(formatCooldown(5 * 3600 + 12 * 60), '5 jam 12 menit')
  assert.equal(formatCooldown(12 * 60), '12 menit')
  assert.equal(formatCooldown(10), '1 menit')
  assert.equal(formatCooldown(0), 'kurang dari 1 menit')
})

test('profil: ganti nama bebas, username sekali per 7 hari', async () => {
  const f = fakes()
  clock.now = 1_700_000_000_000
  const svc = make(f.pool)
  const a = await svc.google('budi@mail.com')
  await svc.google('andi@mail.com')

  // nama: dipangkas spasi, kosong = hapus, >30 / karakter kontrol ditolak
  assert.equal((await svc.updateProfile(a.user.id, { displayName: '  Budi   Santoso ' })).displayName, 'Budi Santoso')
  assert.equal((await svc.updateProfile(a.user.id, { displayName: '' })).displayName, '')
  await assert.rejects(svc.updateProfile(a.user.id, { displayName: 'x'.repeat(31) }), /maksimal 30/)
  await assert.rejects(svc.updateProfile(a.user.id, { displayName: 'a\u0007b' }), /tidak valid/)
  await assert.rejects(svc.updateProfile(a.user.id, {}), /Tidak ada perubahan/)

  // bio: dirapikan, kosong = hapus, >150 karakter / >3 baris / karakter kontrol ditolak
  assert.equal((await svc.updateProfile(a.user.id, { bio: '  Suka   manhwa \r\n\n\n\nAksi & romansa ' })).bio, 'Suka manhwa\n\nAksi & romansa')
  assert.equal((await svc.updateProfile(a.user.id, { bio: '' })).bio, '')
  await assert.rejects(svc.updateProfile(a.user.id, { bio: 'x'.repeat(151) }), /maksimal 150/)
  await assert.rejects(svc.updateProfile(a.user.id, { bio: 'a\nb\nc\nd' }), /maksimal 3 baris/)
  await assert.rejects(svc.updateProfile(a.user.id, { bio: 'a\u0007b' }), /tidak valid/)

  // username: format, bentrok, lalu berhasil -> cooldown aktif
  await assert.rejects(svc.updateProfile(a.user.id, { username: 'a!' }), /Username 3–20/)
  await assert.rejects(svc.updateProfile(a.user.id, { username: 'ANDI' }), /sudah dipakai/)
  const same = await svc.updateProfile(a.user.id, { username: 'budi' })
  assert.equal(same.usernameCooldownSec, 0) // sama persis: bukan penggantian
  const changed = await svc.updateProfile(a.user.id, { username: 'budi_baru' })
  assert.equal(changed.username, 'budi_baru')
  assert.equal(changed.usernameCooldownSec, 7 * 86400)

  // selama cooldown ditolak (429) dengan sisa waktu; nama tetap bisa diganti
  clock.now += 2 * DAY + 3 * 3600 * 1000
  await assert.rejects(svc.updateProfile(a.user.id, { username: 'budi2' }), /dalam 4 hari 21 jam/)
  assert.equal((await svc.updateProfile(a.user.id, { displayName: 'Budi' })).displayName, 'Budi')

  // lewat 7 hari -> boleh lagi
  clock.now += 5 * DAY
  assert.equal((await svc.updateProfile(a.user.id, { username: 'budi2' })).username, 'budi2')
})

test('hapus akun: akun hilang, token lama tidak berlaku, hapus ulang ditolak', async () => {
  const f = fakes()
  const svc = make(f.pool)
  const a = await svc.google('budi@mail.com')
  await svc.deleteAccount(a.user.id)
  assert.equal(f.users.length, 0)
  await assert.rejects(svc.me(`Bearer ${a.token}`), /Akun tidak ditemukan/)
  await assert.rejects(svc.deleteAccount(a.user.id), /Akun tidak ditemukan/)
})

test('profil publik: tanpa email/hash, memuat level & jumlah komentar; tak ada = 404', async () => {
  const f = fakes()
  const svc = make(f.pool)
  const a = await svc.google('budi@mail.com')
  const pub = await svc.publicProfile(a.user.id)
  assert.deepEqual(Object.keys(pub).sort(), ['avatarV', 'bio', 'commentCount', 'displayName', 'frame', 'headerBlur', 'headerPos', 'headerV', 'id', 'isOwner', 'joinedAt', 'level', 'premium', 'readingHidden', 'streak', 'theme', 'username', 'verified', 'xp'])
  assert.equal(pub.username, 'budi')
  assert.equal(pub.level, 1)
  assert.equal(pub.commentCount, 3)
  assert.deepEqual(pub.streak, { current: 0, readToday: false })
  assert.equal(pub.joinedAt, 1_700_000_000_000)
  await assert.rejects(svc.publicProfile(999), /tidak ditemukan/)
})

test('moderasi: akun diblokir ditolak di me(), owner kebal; bisu hanya selama berlaku', async () => {
  const { assertNotMuted } = await import('../src/routes/comments.js')
  const future = Date.now() + 3_600_000
  assert.throws(() => assertNotMuted({ mutedUntil: future, muteReason: 'spam' }), /dibisukan.*spam/)
  assert.doesNotThrow(() => assertNotMuted({ mutedUntil: 0, muteReason: '' }))
  assert.doesNotThrow(() => assertNotMuted({ mutedUntil: Date.now() - 1, muteReason: '' }))

  const row = (email: string) => ({ id: 5, username: 'nakal', email, xp: 0, bannedAt: new Date(), banReason: 'spam' })
  const svc = new AuthService({ async query() { return { rows: [row('a@x.id')] } } } as any, { ...cfg, jwtSecret: 'x', ownerEmails: ['owner@x.id'] } as any, log)
  await assert.rejects(svc.me(`Bearer ${signToken(5, 'x', 60)}`), /diblokir: spam/)
  const owner = new AuthService({ async query() { return { rows: [row('owner@x.id')] } } } as any, { ...cfg, jwtSecret: 'x', ownerEmails: ['owner@x.id'] } as any, log)
  assert.equal((await owner.me(`Bearer ${signToken(5, 'x', 60)}`)).isOwner, true)
})

test('sembunyikan "Sedang dibaca": tersimpan di akun dan terbaca di profil publik', async () => {
  const f = fakes()
  const svc = make(f.pool)
  const a = await svc.google('budi@mail.com')
  assert.equal(a.user.readingHidden, false)
  assert.equal((await svc.updateProfile(a.user.id, { readingHidden: true })).readingHidden, true)
  assert.equal((await svc.publicProfile(a.user.id)).readingHidden, false)
  f.users[0].reading_hidden = true
  assert.equal((await svc.publicProfile(a.user.id)).readingHidden, true)
  assert.equal((await svc.updateProfile(a.user.id, { readingHidden: false })).readingHidden, false)
})

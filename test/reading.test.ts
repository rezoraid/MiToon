import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ReadingService, MAX_READING } from '../src/services/reading.js'

function fakePool() {
  const calls: { sql: string; p: any[] }[] = []
  return { calls, async query(sql: string, p: any[] = []) { calls.push({ sql, p }); return { rows: [] as any[] } } }
}

test('reading.report: validasi id, satu provider, nomor wajib, jumlah dibatasi', async () => {
  const f = fakePool()
  const svc = new ReadingService(f as any)
  await svc.report(7, { seriesId: 'lumos:seri-1', chapterId: 'lumos:bab-12', chapterNumber: ' 12 ', chaptersRead: 5, title: ' Judul\u0000  Komik ' })
  assert.deepEqual(f.calls[0].p, [7, 'lumos:seri-1', 'Judul Komik', 'lumos:bab-12', '12', 5])
  assert.deepEqual(f.calls[1].p, [7, MAX_READING]) // pangkas ke 200 seri terbaru
  await assert.rejects(() => svc.report(7, { seriesId: 'lumos:a', chapterId: 'shinigami:b', chapterNumber: '1' }), /tidak cocok/)
  await assert.rejects(() => svc.report(7, { seriesId: 'bukan-id', chapterId: 'lumos:b', chapterNumber: '1' }))
  await assert.rejects(() => svc.report(7, { seriesId: 'lumos:a', chapterId: 'lumos:b', chapterNumber: '  ' }), /Nomor chapter/)
  await svc.report(7, { seriesId: 'lumos:a', chapterId: 'lumos:b', chapterNumber: '1', chaptersRead: 99999999 })
  assert.equal(f.calls.at(-2)!.p[5], 100_000)
})

test('reading.list: hasMore, urutan dari DB, cover dari katalog, before diteruskan', async () => {
  const rows = [3, 2, 1].map(i => ({ series_id: `lumos:s${i}`, title: `S${i}`, cover: '', provider: 'lumos', chapter_id: `lumos:c${i}`, chapter_number: String(i), chapters_read: i, read_at: new Date(1_700_000_000_000 + i) }))
  let args: any[] = []
  const svc = new ReadingService({ query: async (_s: string, p: any[]) => ((args = p), { rows }) } as any)
  const out = await svc.list(9, 2, 1_700_000_000_999)
  assert.deepEqual(args, [9, 1_700_000_000_999, 3])
  assert.equal(out.hasMore, true)
  assert.deepEqual(out.items.map(i => i.seriesId), ['lumos:s3', 'lumos:s2'])
  assert.equal(out.items[0].readAt, 1_700_000_000_003)
})

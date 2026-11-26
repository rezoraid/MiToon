import type { Pool } from 'pg'
import { checkTarget, cleanComment } from '../core/util.js'
import { AppError, NotFoundError, ValidationError } from '../core/errors.js'
import { isPremiumSql } from '../core/premium.js'
import { levelFor } from '../core/xp.js'
import type { XpService } from './xp.js'

interface CommentItem {
  id: number
  body: string
  createdAt: number
  /** id komentar teratas bila ini balasan; null bila komentar teratas */
  parentId: number | null
  /** nama yang dibalas (hanya bila membalas sebuah balasan); '' bila tidak ada */
  replyTo: string
  likes: number
  dislikes: number
  /** suara pemirsa: 1 = like, -1 = dislike, 0 = belum memilih */
  myVote: number
  /** jumlah balasan (hanya relevan untuk komentar teratas) */
  replyCount: number
  user: { id: number; username: string; displayName: string; avatarV: number; level: number; verified: boolean; isOwner: boolean; premium: boolean }
}

const toItem = (r: any, ownerEmails: string[] = []): CommentItem => {
  const email = String(r.email ?? '').toLowerCase()
  const isOwner = !!email && ownerEmails.includes(email)
  return {
  id: Number(r.id),
  body: r.body,
  createdAt: new Date(r.created_at).getTime(),
  parentId: r.parent_id == null ? null : Number(r.parent_id),
  replyTo: r.reply_to_name ?? '',
  likes: Number(r.likes ?? 0),
  dislikes: Number(r.dislikes ?? 0),
  myVote: Number(r.my_vote ?? 0),
  replyCount: Number(r.reply_count ?? 0),
  user: { id: Number(r.uid), username: r.username, displayName: r.display_name ?? '', avatarV: Number(r.avatar_v ?? 0), level: levelFor(Number(r.xp ?? 0)).level, verified: (r.verified === true || isOwner) && r.verified_hidden !== true, isOwner, premium: r.premium === true }
  }
}

/** Kolom komentar + penulis + jumlah like/dislike/balasan + suara pemirsa (`viewer` = placeholder id pemirsa, mis. '$4'). */
const select = (viewer: string) => `SELECT c.id, c.target, c.body, c.created_at, c.parent_id, u.id AS uid, u.username, u.email, u.display_name, u.xp, u.verified, ${isPremiumSql('u')} AS premium, u.verified_hidden,
                CASE WHEN u.avatar IS NULL THEN 0 ELSE u.avatar_v END AS avatar_v,
                COALESCE(NULLIF(ru.display_name, ''), ru.username) AS reply_to_name,
                (SELECT count(*)::int FROM comment_votes v WHERE v.comment_id = c.id AND v.value = 1) AS likes,
                (SELECT count(*)::int FROM comment_votes v WHERE v.comment_id = c.id AND v.value = -1) AS dislikes,
                COALESCE((SELECT v.value FROM comment_votes v WHERE v.comment_id = c.id AND v.user_id = ${viewer}::int), 0)::int AS my_vote,
                (SELECT count(*)::int FROM comments r WHERE r.parent_id = c.id) AS reply_count
                FROM comments c JOIN users u ON u.id = c.user_id LEFT JOIN users ru ON ru.id = c.reply_to_user_id`

/** Komentar milik sendiri: sama seperti CommentItem + `target` (s:… / c:…) supaya app tahu di mana komentar itu ditulis. */
interface MyCommentItem extends CommentItem {
  target: string
}

const clampLimit = (limit: number) => Math.min(Math.max(Math.floor(limit) || 20, 1), 50)

interface VoteResult {
  likes: number
  dislikes: number
  myVote: number
}

/** Batas kiriman per akun: mencegah banjir komentar walau IP berganti-ganti (rate limit per IP saja tidak cukup). */
const MAX_COMMENTS_PER_MINUTE = 6

/**
 * Komentar per target (seri / chapter): terbaru dulu, paginasi keyset (`before` = id terakhir yang sudah dimuat).
 * Balasan 1 tingkat: selalu menempel ke komentar teratas (urut lama → baru). Like/dislike per pengguna.
 */
export class CommentService {
  constructor(
    private readonly pool: Pool,
    private readonly xp?: XpService,
    private readonly ownerEmails: string[] = []
  ) {}

  private item(r: any): CommentItem {
    return toItem(r, this.ownerEmails)
  }

  /** Komentar teratas. `total` = semua komentar + balasan (untuk judul); `hasMore` = masih ada halaman berikutnya. */
  async list(target: unknown, limit = 20, before?: number, viewer: number | null = null): Promise<{ total: number; hasMore: boolean; items: CommentItem[] }> {
    const t = checkTarget(target)
    const n = clampLimit(limit)
    const [items, total] = await Promise.all([
      this.pool.query(`${select('$4')} WHERE c.target = $1 AND c.parent_id IS NULL AND ($2::bigint IS NULL OR c.id < $2) ORDER BY c.id DESC LIMIT $3`, [t, before ?? null, n + 1, viewer]),
      this.pool.query('SELECT count(*)::int AS n FROM comments WHERE target = $1', [t])
    ])
    return { total: total.rows[0]?.n ?? 0, hasMore: items.rows.length > n, items: items.rows.slice(0, n).map(r => this.item(r)) }
  }

  /** Balasan sebuah komentar teratas, lama → baru; lanjut dengan `after` = id balasan terakhir yang sudah dimuat. */
  async replies(parentId: number, limit = 20, after = 0, viewer: number | null = null): Promise<{ total: number; hasMore: boolean; items: CommentItem[] }> {
    const n = clampLimit(limit)
    const [items, total] = await Promise.all([
      this.pool.query(`${select('$4')} WHERE c.parent_id = $1 AND c.id > $2 ORDER BY c.id ASC LIMIT $3`, [parentId, after, n + 1, viewer]),
      this.pool.query('SELECT count(*)::int AS n FROM comments WHERE parent_id = $1', [parentId])
    ])
    return { total: total.rows[0]?.n ?? 0, hasMore: items.rows.length > n, items: items.rows.slice(0, n).map(r => this.item(r)) }
  }

  /**
   * Kirim komentar, atau balasan bila `replyToId` diisi. Membalas sebuah balasan tetap menempel ke komentar teratasnya
   * dan menyimpan siapa yang dibalas (tampil sebagai "Membalas …").
   */
  async add(userId: number, target: unknown, body: unknown, replyToId?: number | null): Promise<CommentItem> {
    const t = checkTarget(target)
    const text = cleanComment(body)
    const recent = await this.pool.query("SELECT count(*)::int AS n FROM comments WHERE user_id = $1 AND created_at > now() - interval '60 seconds'", [userId])
    if (Number(recent.rows[0]?.n ?? 0) >= MAX_COMMENTS_PER_MINUTE) throw new AppError(429, 'RATE_LIMITED', 'Terlalu cepat, tunggu sebentar sebelum berkomentar lagi')
    let parentId: number | null = null
    let replyToUser: number | null = null
    if (replyToId != null) {
      const { rows: pr } = await this.pool.query('SELECT id, target, parent_id, user_id FROM comments WHERE id = $1', [replyToId])
      const p = pr[0]
      if (!p) throw new NotFoundError('Komentar yang dibalas tidak ditemukan')
      if (p.target !== t) throw new ValidationError('Balasan harus di halaman yang sama')
      if (p.parent_id == null) parentId = Number(p.id)
      else {
        parentId = Number(p.parent_id)
        replyToUser = Number(p.user_id)
      }
    }
    const { rows } = await this.pool.query('INSERT INTO comments (target, user_id, body, parent_id, reply_to_user_id) VALUES ($1, $2, $3, $4, $5) RETURNING id', [t, userId, text, parentId, replyToUser])
    await this.xp?.award(userId, 'comment', String(rows[0].id)).catch(() => 0)
    const full = await this.pool.query(`${select('$2')} WHERE c.id = $1`, [rows[0].id, userId])
    return this.item(full.rows[0])
  }

  /** Like (1), dislike (-1), atau batalkan (0). Penulis dapat XP kecil saat disukai orang lain. */
  async vote(userId: number, id: number, value: unknown): Promise<VoteResult> {
    if (value !== 1 && value !== -1 && value !== 0) throw new ValidationError('Suara harus 1, -1, atau 0')
    const { rows: cr } = await this.pool.query('SELECT user_id FROM comments WHERE id = $1', [id])
    if (!cr[0]) throw new NotFoundError('Komentar tidak ditemukan')
    if (value === 0) await this.pool.query('DELETE FROM comment_votes WHERE comment_id = $1 AND user_id = $2', [id, userId])
    else await this.pool.query('INSERT INTO comment_votes (comment_id, user_id, value) VALUES ($1, $2, $3) ON CONFLICT (comment_id, user_id) DO UPDATE SET value = EXCLUDED.value, created_at = CASE WHEN comment_votes.value <> EXCLUDED.value THEN now() ELSE comment_votes.created_at END', [id, userId, value])
    const author = Number(cr[0].user_id)
    if (value === 1 && author !== userId) await this.xp?.award(author, 'liked', `${id}:${userId}`).catch(() => 0)
    const { rows } = await this.pool.query('SELECT count(*) FILTER (WHERE value = 1)::int AS likes, count(*) FILTER (WHERE value = -1)::int AS dislikes FROM comment_votes WHERE comment_id = $1', [id])
    return { likes: Number(rows[0]?.likes ?? 0), dislikes: Number(rows[0]?.dislikes ?? 0), myVote: value }
  }

  /** Komentar & balasan milik `userId` (riwayat sendiri, atau profil orang lain bila `viewer` = pemirsa), terbaru dulu; lanjut dengan `before` = id terakhir yang sudah dimuat. */
  async mine(userId: number, limit = 20, before?: number, viewer: number | null = userId): Promise<{ total: number; hasMore: boolean; items: MyCommentItem[] }> {
    const n = clampLimit(limit)
    const [items, total] = await Promise.all([
      this.pool.query(`${select('$4')} WHERE c.user_id = $1 AND ($2::bigint IS NULL OR c.id < $2) ORDER BY c.id DESC LIMIT $3`, [userId, before ?? null, n + 1, viewer]),
      this.pool.query('SELECT count(*)::int AS n FROM comments WHERE user_id = $1', [userId])
    ])
    return { total: total.rows[0]?.n ?? 0, hasMore: items.rows.length > n, items: items.rows.slice(0, n).map(r => ({ ...this.item(r), target: String(r.target) })) }
  }

  /** Hanya penulis yang bisa menghapus komentarnya (balasan & suara ikut terhapus). */
  async remove(userId: number, id: number): Promise<void> {
    const { rowCount } = await this.pool.query('DELETE FROM comments WHERE id = $1 AND user_id = $2', [id, userId])
    if (!rowCount) throw new NotFoundError('Komentar tidak ditemukan')
  }
}

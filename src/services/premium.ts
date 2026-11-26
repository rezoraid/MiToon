import { randomUUID } from 'node:crypto'
import type { Pool } from 'pg'
import { AppError, NotFoundError, ValidationError } from '../core/errors.js'
import {
  FRAMES, MAX_HEADER_BLUR, METHODS, PLANS, PREMIUM_XP_MULT, THEMES, methodById, planById, planSaving, type MethodGroup
} from '../core/premium.js'
import type { PaymentGateway } from './sociabuzz.js'
import { SB_METHODS } from './sociabuzz.js'

type Log = { warn: (o: object, m?: string) => void; error: (o: object, m?: string) => void; info?: (o: object, m?: string) => void }

export interface OrderDto {
  id: string
  plan: string
  months: number
  method: string
  amount: number
  total: number
  fee: number
  status: 'pending' | 'paid' | 'expired' | 'failed'
  createdAt: number
  expiresAt: number
  paidAt: number
  pay: { kind: MethodGroup; qr?: string; redirectUrl?: string; account?: string; bankName?: string; bank?: string }
}

interface OrderRow {
  id: string
  user_id: number
  plan: string
  months: number
  method: string
  amount: number
  total: number
  status: string
  pending_url: string | null
  pay_info: Record<string, string> | null
  created_at: Date | string
  expires_at: Date | string
  paid_at: Date | string | null
  checked_at: Date | string | null
}

const ms = (v: Date | string | null | undefined): number => (v ? new Date(v).getTime() : 0)

/** Selisih (ms) minimum antar pengecekan status ke Sociabuzz untuk satu pesanan (klien polling ±4 dtk). */
const CHECK_GAP_MS = 3_000
/** Pesanan masih "pending" lewat batas kedaluwarsa + jeda ini -> ditandai expired. */
const EXPIRE_GRACE_MS = 60 * 60_000
/** "not_found" tepat setelah pesanan dibuat bisa sementara; baru dianggap gagal setelah ini. */
const NOT_FOUND_GRACE_MS = 2 * 60_000
const MAX_ORDERS_PER_10_MIN = 5
const RECONCILE_EVERY_MS = 30_000

export const toOrderDto = (r: OrderRow): OrderDto => {
  const m = methodById(r.method)
  const info = r.pay_info ?? {}
  const kind: MethodGroup = m?.group ?? (SB_METHODS[r.method]?.group === 'bank' ? 'bank' : 'ewallet')
  return {
    id: r.id,
    plan: r.plan,
    months: r.months,
    method: r.method,
    amount: r.amount,
    total: r.total,
    fee: Math.max(0, r.total - r.amount),
    status: (['pending', 'paid', 'expired', 'failed'].includes(r.status) ? r.status : 'pending') as OrderDto['status'],
    createdAt: ms(r.created_at),
    expiresAt: ms(r.expires_at),
    paidAt: ms(r.paid_at),
    pay: {
      kind,
      qr: info.qr_string,
      redirectUrl: info.redirect_url || info.payment_link,
      account: info.account_number,
      bankName: info.bank_name,
      bank: info.bank
    }
  }
}

export interface StylePatch {
  theme?: string | null
  frame?: string | null
  headerBlur?: number
  headerPos?: number
}

export class PremiumService {
  private timer: NodeJS.Timeout | undefined
  private running = false
  private readonly creating = new Set<number>()

  constructor(
    private readonly pool: Pool,
    private readonly gw: PaymentGateway,
    private readonly log: Log,
    private readonly enabled = true
  ) {}

  // ------------------------------------------------------------------ katalog

  catalog() {
    return {
      enabled: this.enabled,
      xpMultiplier: PREMIUM_XP_MULT,
      plans: PLANS.map(p => ({ id: p.id, label: p.label, months: p.months, price: p.price, saving: planSaving(p), perMonth: Math.round(p.price / p.months) })),
      methods: METHODS.map(m => ({ id: m.id, name: m.name, group: m.group, min: m.min })),
      themes: [...THEMES],
      frames: [...FRAMES]
    }
  }

  // ------------------------------------------------------------------ pesanan

  /** Buat pesanan (atau lanjutkan yang sama & masih berlaku). Harga dari server. */
  async createOrder(userId: number, username: string, planId: unknown, methodId: unknown): Promise<OrderDto> {
    if (!this.enabled) throw new AppError(503, 'PREMIUM_DISABLED', 'Pembelian Premium sedang dinonaktifkan')
    const plan = planById(planId)
    if (!plan) throw new ValidationError('Paket tidak valid')
    const method = methodById(methodId)
    if (!method) throw new ValidationError('Metode pembayaran tidak valid')
    if (plan.price < method.min) throw new ValidationError(`${method.name} minimal Rp${method.min.toLocaleString('id-ID')}`)

    if (this.creating.has(userId)) throw new AppError(429, 'RATE_LIMITED', 'Pembayaran sedang dibuat, tunggu sebentar')
    this.creating.add(userId)
    try {
      const resume = await this.pool.query(
        `SELECT * FROM premium_orders WHERE user_id = $1 AND plan = $2 AND method = $3 AND status = 'pending' AND expires_at > now() + interval '2 minutes'
         ORDER BY created_at DESC LIMIT 1`,
        [userId, plan.id, method.id]
      )
      if (resume.rows[0]) return toOrderDto(resume.rows[0] as OrderRow)

      const recent = await this.pool.query(`SELECT count(*)::int AS n FROM premium_orders WHERE user_id = $1 AND created_at > now() - interval '10 minutes'`, [userId])
      if (Number(recent.rows[0]?.n ?? 0) >= MAX_ORDERS_PER_10_MIN) throw new AppError(429, 'RATE_LIMITED', 'Terlalu banyak percobaan, coba lagi beberapa menit lagi')

      const id = randomUUID()
      let pay
      try {
        pay = await this.gw.create(plan.price, { method: method.id, name: `MiToon @${username}`.slice(0, 40), message: `Premium ${plan.label} #${id.slice(0, 8)}` })
      } catch (e) {
        this.log.warn({ err: (e as Error).message, method: method.id }, 'premium: gagal membuat pembayaran')
        throw new AppError(502, 'UPSTREAM_ERROR', 'Gagal membuat pembayaran. Coba lagi atau pilih metode lain.')
      }
      const { rows } = await this.pool.query(
        `INSERT INTO premium_orders (id, user_id, plan, months, method, amount, total, sb_order, sb_inv, pending_url, pay_info, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11::jsonb, $12) RETURNING *`,
        [id, userId, plan.id, plan.months, method.id, plan.price, Math.max(pay.total, plan.price), pay.orderToken, pay.invId, pay.pendingUrl, JSON.stringify(pay.info), pay.expiresAt]
      )
      return toOrderDto(rows[0] as OrderRow)
    } finally {
      this.creating.delete(userId)
    }
  }

  /** Status satu pesanan milik pengguna; sekaligus memeriksa ke Sociabuzz (dibatasi) dan mengaktifkan Premium bila sudah dibayar. */
  async getOrder(userId: number, id: string): Promise<OrderDto> {
    if (typeof id !== 'string' || id.length > 64) throw new NotFoundError('Pesanan tidak ditemukan')
    const { rows } = await this.pool.query('SELECT * FROM premium_orders WHERE id = $1 AND user_id = $2', [id, userId])
    if (!rows[0]) throw new NotFoundError('Pesanan tidak ditemukan')
    return toOrderDto(await this.settle(rows[0] as OrderRow))
  }

  /** Pesanan terbaru yang masih menunggu bayar (atau baru lunas), untuk melanjutkan pembayaran yang terputus. */
  async latest(userId: number): Promise<OrderDto | null> {
    const { rows } = await this.pool.query(
      `SELECT * FROM premium_orders WHERE user_id = $1 AND created_at > now() - interval '26 hours' ORDER BY created_at DESC LIMIT 1`,
      [userId]
    )
    if (!rows[0]) return null
    const o = await this.settle(rows[0] as OrderRow)
    if (o.status === 'pending' && ms(o.expires_at) > Date.now()) return toOrderDto(o)
    if (o.status === 'paid' && Date.now() - ms(o.paid_at) < 5 * 60_000) return toOrderDto(o)
    return null
  }

  /** Periksa status ke Sociabuzz dan perbarui pesanan. Aman dipanggil berulang & bersamaan (aktivasi atomik, sekali saja). */
  private async settle(o: OrderRow): Promise<OrderRow> {
    if (o.status !== 'pending') return o
    const now = Date.now()
    if (o.checked_at && now - ms(o.checked_at) < CHECK_GAP_MS) return o
    await this.pool.query('UPDATE premium_orders SET checked_at = now() WHERE id = $1', [o.id])

    let st: { status: string } | null = null
    if (o.pending_url) st = await this.gw.status(o.pending_url).catch(e => (this.log.warn({ err: (e as Error).message, order: o.id }, 'premium: cek status gagal'), null))

    if (st?.status === 'success') return (await this.activate(o.id)) ?? o
    let next: 'expired' | 'failed' | null = null
    if (st?.status === 'expired') next = 'expired'
    else if (st?.status === 'failed') next = 'failed'
    else if (st?.status === 'not_found' && now - ms(o.created_at) > NOT_FOUND_GRACE_MS) next = 'failed'
    else if (now > ms(o.expires_at) + EXPIRE_GRACE_MS) next = 'expired'
    if (!next) return o
    const { rows } = await this.pool.query(`UPDATE premium_orders SET status = $2 WHERE id = $1 AND status = 'pending' RETURNING *`, [o.id, next])
    return (rows[0] as OrderRow | undefined) ?? o
  }

  /**
   * Tandai lunas + perpanjang langganan dalam SATU pernyataan SQL (atomik): hanya pemanggil pertama yang mengubah status,
   * jadi pembayaran yang sama tidak pernah menambah masa aktif dua kali. Pembayaran yang telat (status expired) tetap dihormati.
   */
  private async activate(id: string): Promise<OrderRow | null> {
    await this.pool.query(
      `WITH o AS (
         UPDATE premium_orders SET status = 'paid', paid_at = now() WHERE id = $1 AND status IN ('pending', 'expired') RETURNING user_id, months
       )
       UPDATE users u SET premium_until = GREATEST(now(), COALESCE(u.premium_until, now())) + make_interval(months => o.months)
         FROM o WHERE u.id = o.user_id`,
      [id]
    )
    const { rows } = await this.pool.query('SELECT * FROM premium_orders WHERE id = $1', [id])
    return (rows[0] as OrderRow | undefined) ?? null
  }

  /** Periksa semua pesanan yang masih menunggu (dipakai timer latar, supaya Premium aktif walau app sudah ditutup). */
  async reconcile(): Promise<number> {
    if (this.running) return 0
    this.running = true
    let paid = 0
    try {
      const { rows } = await this.pool.query(`SELECT * FROM premium_orders WHERE status = 'pending' AND created_at > now() - interval '26 hours' ORDER BY created_at LIMIT 25`)
      for (const r of rows as OrderRow[]) {
        const after = await this.settle(r).catch(() => r)
        if (after.status === 'paid') paid++
      }
    } catch (e) {
      this.log.warn({ err: (e as Error).message }, 'premium: reconcile gagal')
    } finally {
      this.running = false
    }
    return paid
  }

  start() {
    if (this.timer || !this.enabled) return
    this.timer = setInterval(() => void this.reconcile(), RECONCILE_EVERY_MS)
    this.timer.unref?.()
  }
  stop() {
    if (this.timer) clearInterval(this.timer)
    this.timer = undefined
  }

  // ------------------------------------------------------------------ tampilan profil Premium

  /**
   * Tema, frame, blur & posisi background header. Mengubah (bukan mengosongkan) hanya untuk Premium aktif.
   * Kosong ('' / null) = kembali ke bawaan; boleh kapan saja.
   */
  async setStyle(userId: number, active: boolean, patch: StylePatch): Promise<void> {
    const setTheme = patch.theme !== undefined
    const theme = patch.theme ? String(patch.theme) : null
    if (theme && !(THEMES as readonly string[]).includes(theme)) throw new ValidationError('Tema tidak valid')
    const setFrame = patch.frame !== undefined
    const frame = patch.frame ? String(patch.frame) : null
    if (frame && !(FRAMES as readonly string[]).includes(frame)) throw new ValidationError('Frame tidak valid')
    const setBlur = patch.headerBlur !== undefined
    const setPos = patch.headerPos !== undefined
    const blur = Math.round(Number(patch.headerBlur ?? 0))
    const pos = Math.round(Number(patch.headerPos ?? 50))
    if (setBlur && !(blur >= 0 && blur <= MAX_HEADER_BLUR)) throw new ValidationError(`Blur harus 0–${MAX_HEADER_BLUR}`)
    if (setPos && !(pos >= 0 && pos <= 100)) throw new ValidationError('Posisi harus 0–100')
    if (!setTheme && !setFrame && !setBlur && !setPos) throw new ValidationError('Tidak ada perubahan')
    if (!active && (theme || frame || setBlur || setPos)) throw new AppError(403, 'PREMIUM_REQUIRED', 'Fitur ini khusus Premium')

    await this.pool.query(
      `UPDATE users SET
         theme = CASE WHEN $2::boolean THEN $3::text ELSE theme END,
         frame = CASE WHEN $4::boolean THEN $5::text ELSE frame END,
         header_blur = CASE WHEN $6::boolean THEN $7::smallint ELSE header_blur END,
         header_pos = CASE WHEN $8::boolean THEN $9::smallint ELSE header_pos END
       WHERE id = $1`,
      [userId, setTheme, theme, setFrame, frame, setBlur, blur, setPos, pos]
    )
  }

  async setHeader(userId: number, jpeg: Buffer): Promise<void> {
    await this.pool.query('UPDATE users SET header_bg = $2, header_v = header_v + 1 WHERE id = $1', [userId, jpeg])
  }

  async clearHeader(userId: number): Promise<void> {
    await this.pool.query('UPDATE users SET header_bg = NULL, header_v = header_v + 1 WHERE id = $1', [userId])
  }

  /** Gambar header; hanya dilayani selama pemiliknya Premium aktif. */
  async headerOf(userId: number): Promise<Buffer | null> {
    const { rows } = await this.pool.query(
      `SELECT header_bg FROM users WHERE id = $1 AND header_bg IS NOT NULL AND (premium OR (premium_until IS NOT NULL AND premium_until > now()))`,
      [userId]
    )
    return (rows[0]?.header_bg as Buffer | null) ?? null
  }
}

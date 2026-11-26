import type { MethodGroup } from '../core/premium.js'

/**
 * Gateway pembayaran Sociabuzz (port dari modul sociabuzz.js, tanpa axios/cheerio).
 * Alur: buka halaman donasi -> kirim form -> buka halaman bayar -> buat transaksi (QRIS / e-wallet / VA).
 * Status dibaca dari halaman "pending" milik invoice itu. Tiap pembayaran memakai cookie jar sendiri,
 * jadi beberapa pembuatan pembayaran bersamaan tidak saling menimpa token CSRF.
 */

export interface SbMethod {
  type_payment: string
  source_payment: string
  group: MethodGroup | 'retail'
  min: number
}

/** Metode yang dikenal Sociabuzz (id = payment_method). */
export const SB_METHODS: Record<string, SbMethod> = {
  qris: { type_payment: 'qris', source_payment: 'xendit', group: 'qris', min: 1000 },
  gopay: { type_payment: 'ewallet_id', source_payment: 'midtrans', group: 'ewallet', min: 1000 },
  dana: { type_payment: 'ewallet_id', source_payment: 'xendit', group: 'ewallet', min: 1000 },
  linkaja: { type_payment: 'ewallet_id', source_payment: 'xendit', group: 'ewallet', min: 1000 },
  shopeepay_idr: { type_payment: 'ewallet_id', source_payment: '2c2p', group: 'ewallet', min: 1000 },
  bca: { type_payment: 'bank_transfer', source_payment: 'midtrans', group: 'bank', min: 10000 },
  mandiri: { type_payment: 'bank_transfer', source_payment: 'xendit', group: 'bank', min: 10000 },
  bri: { type_payment: 'bank_transfer', source_payment: 'xendit', group: 'bank', min: 10000 },
  bni: { type_payment: 'bank_transfer', source_payment: 'xendit', group: 'bank', min: 10000 }
}

/** Masa berlaku pembayaran: QRIS & e-wallet 30 menit, transfer bank (VA) 24 jam. */
const EXPIRY_MS: Record<string, number> = { qris: 30 * 60_000, ewallet: 30 * 60_000, bank: 24 * 3_600_000 }

export type SbStatus = 'pending' | 'success' | 'expired' | 'not_found' | 'failed' | 'unknown'

export interface GatewayPayment {
  /** token pesanan Sociabuzz */
  orderToken: string
  /** id invoice Sociabuzz (dipakai membaca status) */
  invId: string
  /** halaman status invoice (dibaca server, tidak dikirim ke klien) */
  pendingUrl: string
  /** total yang harus dibayar (sudah termasuk biaya bila ada) */
  total: number
  expiresAt: Date
  /** petunjuk bayar untuk klien: qr_string / account_number / bank_name / bank / redirect_url / payment_link */
  info: Record<string, string>
}

export interface PaymentGateway {
  create(amount: number, opts: { method: string; name: string; message: string }): Promise<GatewayPayment>
  status(pendingUrl: string): Promise<{ status: SbStatus; title: string }>
}

const UA =
  'Mozilla/5.0 (Linux; Android 13; SM-A057F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36'

/** Header standar browser; WAF Sociabuzz menolak request yang terlihat seperti bot. */
const BROWSER_HEADERS: Record<string, string> = {
  'accept-language': 'id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7',
  'cache-control': 'no-cache'
}

export const cleanAmount = (v: unknown): number => Number(String(v ?? '').replace(/[^\d]/g, ''))

/** Nilai <input name="..."> dari HTML (atribut name & value bisa berurutan apa saja). */
export function inputValue(html: string, name: string): string | undefined {
  for (const m of html.matchAll(/<input\b[^>]*>/gi)) {
    const tag = m[0]
    if (!new RegExp(`\\bname\\s*=\\s*["']${name}["']`, 'i').test(tag)) continue
    const v = /\bvalue\s*=\s*"([^"]*)"|\bvalue\s*=\s*'([^']*)'/i.exec(tag)
    const val = v ? (v[1] ?? v[2]) : undefined
    if (val) return val
  }
  return undefined
}

/** Status dari judul halaman pending Sociabuzz (urutan pengecekan sama dengan modul asli). */
export function statusFromTitle(title: string): SbStatus {
  const t = title.toLowerCase()
  if (t.includes('pending')) return 'pending'
  if (t.includes('success')) return 'success'
  if (t.includes('expired')) return 'expired'
  if (t.includes('not found')) return 'not_found'
  if (t.includes('fail')) return 'failed'
  return 'unknown'
}

class Session {
  private readonly jar = new Map<string, string>()
  constructor(
    private readonly http: typeof fetch,
    private readonly timeoutMs: number
  ) {}

  set(name: string, value: string) {
    if (name && value) this.jar.set(name, value)
  }
  get(name: string) {
    return this.jar.get(name)
  }
  names() {
    return this.jar.keys()
  }

  private absorb(res: Response) {
    const h = res.headers as Headers & { getSetCookie?: () => string[] }
    for (const c of h.getSetCookie?.() ?? []) {
      const kv = c.split(';')[0]
      const i = kv.indexOf('=')
      if (i > 0) this.jar.set(kv.slice(0, i).trim(), kv.slice(i + 1).trim())
    }
  }

  /** GET/POST dengan cookie jar; redirect diikuti manual supaya cookie tiap lompatan ikut tersimpan. */
  async request(url: string, init: { method?: string; headers?: Record<string, string>; body?: string } = {}): Promise<{ status: number; text: string }> {
    let u = url
    let method = init.method ?? 'GET'
    let body = init.body
    for (let hop = 0; hop < 6; hop++) {
      const cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join('; ')
      const res = await this.http(u, {
        method,
        body,
        redirect: 'manual',
        signal: AbortSignal.timeout(this.timeoutMs),
        headers: { 'user-agent': UA, ...BROWSER_HEADERS, ...(init.headers ?? {}), ...(cookie ? { cookie } : {}) }
      })
      this.absorb(res)
      const loc = res.headers.get('location')
      if (res.status >= 300 && res.status < 400 && loc) {
        u = new URL(loc, u).toString()
        if (res.status === 303 || ((res.status === 301 || res.status === 302) && method === 'POST')) {
          method = 'GET'
          body = undefined
        }
        continue
      }
      return { status: res.status, text: await res.text() }
    }
    throw new Error('Terlalu banyak redirect')
  }
}

const parseJson = (t: string): any => {
  try {
    return JSON.parse(t)
  } catch {
    return null
  }
}

export class SociabuzzGateway implements PaymentGateway {
  private readonly base: string
  private readonly username: string
  private readonly http: typeof fetch
  private readonly timeoutMs: number

  constructor(opts: { username: string; baseUrl?: string; http?: typeof fetch; timeoutMs?: number }) {
    this.username = opts.username
    this.base = (opts.baseUrl ?? 'https://sociabuzz.com').replace(/\/+$/, '')
    this.http = opts.http ?? fetch
    this.timeoutMs = opts.timeoutMs ?? 20_000
  }

  async create(amountIn: number, o: { method: string; name: string; message: string }): Promise<GatewayPayment> {
    let lastErr: unknown
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        return await this.createOnce(amountIn, o)
      } catch (e) {
        lastErr = e
        // Hanya ulang untuk penolakan CSRF/verifikasi (sesi baru = token baru); error lain langsung dilempar.
        if (!/security verification|csrf|refresh/i.test(String((e as Error)?.message))) throw e
        console.warn(`sociabuzz: verifikasi ditolak (percobaan ${attempt}/3)`, (e as Error).message)
        await new Promise((r) => setTimeout(r, 400 * attempt))
      }
    }
    throw lastErr
  }

  private async createOnce(amountIn: number, o: { method: string; name: string; message: string }): Promise<GatewayPayment> {
    const pm = SB_METHODS[o.method]
    if (!pm) throw new Error(`Metode "${o.method}" tidak tersedia`)
    const amount = cleanAmount(amountIn)
    if (!amount || amount < pm.min) throw new Error(`Minimal Rp ${pm.min} untuk ${o.method}`)

    const s = new Session(this.http, this.timeoutMs)
    const donateUrl = `${this.base}/${this.username}/donate`

    const home = await s.request(donateUrl, { headers: { accept: 'text/html' } })
    if (home.status >= 400) throw new Error(`Halaman donasi tidak bisa dibuka (${home.status})`)
    const csrf = inputValue(home.text, 'sb_token_csrf')
    if (!csrf) throw new Error('CSRF token halaman donasi tidak ditemukan (struktur Sociabuzz berubah atau username salah)')

    // Token CSRF form harus sama dengan cookie-nya; server kadang tidak mengirim cookie itu lewat Set-Cookie.
    s.set('sociabuzz_sb_cookie_csrf', s.get('csrf_cookie_name') ?? csrf)

    const form = new URLSearchParams({
      sb_token_csrf: s.get('csrf_cookie_name') ?? csrf, currency: 'IDR', amount: String(amount), qty: '1', support_duration: '30',
      note: o.message, fullname: o.name, email: `donatur${Date.now()}@gmail.com`, is_agree: '1', years18: '1',
      is_vote: '0', is_voice: '0', is_mediashare: '0', is_gif: '0', is_sound: '0', is_voicy: '0', vote_id: '',
      ms_maxtime: '', start_from: '0', ms_starthour: '0', ms_startminute: '0', ms_startsecond: '0', spin_check: '0',
      prev_url: donateUrl, hide_email: '0', is_tiktok: '0', tiktok_duration: '0', is_instagram: '0', instagram_duration: '0',
      wishlist_id: '', quickpay: '0'
    })
    const sub = await s.request(`${donateUrl}/get-form-queue`, {
      method: 'POST',
      body: form.toString(),
      headers: { 'content-type': 'application/x-www-form-urlencoded', origin: this.base, referer: donateUrl, 'x-requested-with': 'XMLHttpRequest' }
    })
    const subJson = parseJson(sub.text)
    if (!subJson || (subJson.success !== 'true' && subJson.success !== true)) {
      console.error('sociabuzz form ditolak', {
        homeStatus: home.status,
        subStatus: sub.status,
        cookies: [...s.names()],
        home: home.text.slice(0, 120).replace(/\s+/g, ' '),
        sub: sub.text.slice(0, 300).replace(/\s+/g, ' ')
      })
      throw new Error(subJson?.content?.form_alert || 'Sociabuzz menolak form donasi')
    }

    const paymentUrl = String(subJson.content?.redirect ?? '')
    const token = paymentUrl.split('/payment/x/')[1]?.split(/[?#]/)[0]
    if (!token) throw new Error('Token pesanan Sociabuzz tidak ditemukan di URL redirect')

    const pay = await s.request(paymentUrl, { headers: { accept: 'text/html' } })
    let csrf2 = inputValue(pay.text, 'sb_token_csrf')
    if (csrf2) s.set('sociabuzz_sb_cookie_csrf', csrf2)

    const qs = new URLSearchParams({
      amount: String(amount), currency: 'IDR', base_amount: String(amount), base_currency: 'IDR', currency_def: 'IDR',
      convertion: 'IDR', country: 'Indonesia', feature: 'TRIBE', is_borne_fee: '1', risk: '', message: '', direct: '',
      service_fee: '1', token, country_account: ''
    })
    await s.request(`${this.base}/payment/pay/setting?${qs.toString()}`)

    const c = s.get('csrf_cookie_name')
    if (c) {
      s.set('sociabuzz_sb_cookie_csrf', c)
      csrf2 = c
    }
    if (!csrf2) throw new Error('CSRF token tahap pembayaran tidak ditemukan')

    const created = await s.request(`${this.base}/payment/send/create`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', origin: this.base, referer: paymentUrl, 'x-requested-with': 'XMLHttpRequest' },
      body: JSON.stringify({
        sb_token_csrf: csrf2, order_id: token, final_currency: 'IDR', currency_def: 'IDR', payment_method: o.method,
        type_payment: pm.type_payment, source_payment: pm.source_payment, country: 'ID', country_pay: 'Indonesia'
      })
    })
    const rd = parseJson(created.text)
    if (!rd?.status) throw new Error(`Sociabuzz gagal membuat transaksi: ${created.text.slice(0, 200)}`)
    // Tanpa inv_id status tidak bisa diverifikasi -> jangan terima pesanan yang tak bisa dilacak.
    if (!rd.inv_id) throw new Error('Sociabuzz tidak mengembalikan id invoice')

    const d = rd.data ?? {}
    const total = cleanAmount(d.amount || d.total || amount)
    const info: Record<string, string> = {}
    if (d.qr_string) info.qr_string = String(d.qr_string)
    if (d.account_number) info.account_number = String(d.account_number)
    if (d.name) info.bank_name = String(d.name)
    if (d.bank) info.bank = String(d.bank)
    if (d.redirect_url) info.redirect_url = String(d.redirect_url)
    if (d.payment_link) info.payment_link = String(d.payment_link)

    const group = pm.group === 'retail' ? 'bank' : pm.group
    const invId = String(rd.inv_id)
    return {
      orderToken: token,
      invId,
      pendingUrl: `${this.base}/payment/pending?type=${encodeURIComponent(String(rd.payment_method ?? o.method))}&inv_id=${encodeURIComponent(invId)}`,
      total,
      expiresAt: new Date(Date.now() + (EXPIRY_MS[group] ?? 30 * 60_000)),
      info
    }
  }

  async status(pendingUrl: string): Promise<{ status: SbStatus; title: string }> {
    // Hanya halaman milik Sociabuzz yang boleh dibaca.
    if (new URL(pendingUrl).origin !== new URL(this.base).origin) throw new Error('URL status tidak valid')
    const res = await this.http(pendingUrl, { headers: { 'user-agent': UA, accept: 'text/html' }, signal: AbortSignal.timeout(15_000) })
    const html = await res.text()
    const title = (/<title>([^<]+)/i.exec(html)?.[1] ?? '').trim()
    return { status: statusFromTitle(title), title }
  }
}

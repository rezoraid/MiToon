export class AppError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message)
    this.name = new.target.name
  }
}
export class ValidationError extends AppError {
  constructor(message: string) {
    super(400, 'BAD_REQUEST', message)
  }
}
export class UnauthorizedError extends AppError {
  constructor(message = 'Token admin tidak valid') {
    super(401, 'UNAUTHORIZED', message)
  }
}
export class NotFoundError extends AppError {
  constructor(message = 'Not found') {
    super(404, 'NOT_FOUND', message)
  }
}
export class UpstreamError extends AppError {
  constructor(
    message: string,
    readonly upstreamStatus?: number
  ) {
    super(502, 'UPSTREAM_ERROR', message)
  }
}
export class UnavailableError extends AppError {
  constructor(message: string) {
    super(503, 'PROVIDER_UNAVAILABLE', message)
  }
}
export class TimeoutError extends AppError {
  constructor(message = 'Upstream timeout') {
    super(504, 'UPSTREAM_TIMEOUT', message)
  }
}

/** Ubah error apa pun (fetch abort, parse, dll) menjadi AppError. */
export function normalizeError(e: unknown): AppError | Error {
  if (e instanceof AppError) return e
  const err = e as { name?: string; message?: string }
  if (err?.name === 'TimeoutError' || err?.name === 'AbortError') return new TimeoutError()
  return new UpstreamError(err?.message || 'Upstream failure')
}
export class ConflictError extends AppError {
  constructor(message: string) {
    super(409, 'CONFLICT', message)
  }
}
export class NotConfiguredError extends AppError {
  constructor(message: string) {
    super(501, 'NOT_CONFIGURED', message)
  }
}

/**
 * Error dari driver PostgreSQL / koneksi -> status HTTP yang tepat (bukan 500 polos).
 *  - 22xxx (angka di luar rentang, format salah, terlalu panjang) = input pengguna yang buruk -> 400
 *  - 23505 / 23503 (bentrok unik / relasi hilang) -> 409
 *  - timeout query, koneksi habis/putus, DB restart -> 503 (sementara, aman diulang)
 * null = bukan error database (tetap diperlakukan sebagai 500).
 */
export function dbErrorStatus(e: unknown): { status: number; code: string; message: string } | null {
  const err = e as { code?: unknown; message?: unknown } | null
  const c = typeof err?.code === 'string' ? err.code : ''
  const msg = typeof err?.message === 'string' ? err.message : ''
  if (/^22/.test(c)) return { status: 400, code: 'BAD_REQUEST', message: 'Parameter tidak valid' }
  if (c === '23505' || c === '23503') return { status: 409, code: 'CONFLICT', message: 'Data bentrok atau tidak ditemukan' }
  const busy =
    c === '57014' || c === '53300' || c === '57P01' || c === '57P02' || c === '57P03' || /^08/.test(c) ||
    ['ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT'].includes(c) ||
    /timeout exceeded when trying to connect|connection terminated|too many clients|canceling statement due to statement timeout/i.test(msg)
  return busy ? { status: 503, code: 'DB_UNAVAILABLE', message: 'Server sedang sibuk, coba lagi sebentar' } : null
}

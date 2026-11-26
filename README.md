# MiToon Backend

REST API (Node 22 + TypeScript + Fastify) untuk aplikasi MiToon: agregator komik dari **Shinigami** dan **Lumos** dengan format data seragam, fallback otomatis antar provider, cache Redis (stale-while-revalidate), dan PostgreSQL sebagai data utama. Dokumentasi interaktif di `/docs`.

## Jalankan

```bash
# Docker (API + PostgreSQL + Redis)
cp .env.example .env     # isi POSTGRES_PASSWORD & JWT_SECRET
docker compose up -d --build   # API di :3000, dokumentasi di /docs

# Lokal tanpa Docker
cp .env.example .env     # aktifkan DATABASE_URL & REDIS_URL di bagian bawah
npm install && npm run build && npm start
```

Push realtime (opsional): isi `FIREBASE_SERVICE_ACCOUNT` dengan JSON service account Firebase (mentah atau base64). Tiap chapter baru dikirim sebagai pesan data FCM ke topic `s_<sha1(seriesId)[0..24]>`; aplikasi subscribe otomatis untuk komik di bookmark. Kosong = push nonaktif.

Migration berjalan otomatis saat start (`npm run migrate` untuk manual). Env wajib: `DATABASE_URL`, `REDIS_URL` (di compose diisi otomatis), `JWT_SECRET` (produksi). `/docs` (Swagger) mati di produksi (`NODE_ENV=production`); aktifkan dengan `ENABLE_DOCS=1`. `POST /auth/logout-all` mencabut semua token akun. Opsional `TRUST_PROXY` (jumlah reverse proxy tepercaya, default `1`; jangan dikosongkan ke `true`). Pengaturan lain (TTL, rate limit, warmer) ada di `src/config.ts`.

| Perintah | Fungsi |
|---|---|
| `npm test` | unit test (`TEST_DATABASE_URL=... npm test` untuk tes integrasi DB) |
| `npm run typecheck` | cek tipe tanpa build |
| `npm run smoke [provider]` | uji ke situs asli: latest → search → detail → chapter |

## Deploy

**Server / container apa pun:** jalankan `docker compose up -d --build` (butuh Docker + plugin compose), atau build `Dockerfile` sendiri dengan `DATABASE_URL`, `REDIS_URL`, `JWT_SECRET` dari environment. API mendengarkan di `PORT` (default 3000); `GET /health` untuk health check. Pasang reverse proxy/HTTPS pilihanmu di depannya (Android memblokir HTTP polos untuk APK). Update: tarik kode baru lalu `docker compose up -d --build`; data aman di volume `pgdata`.

**Vercel (serverless):** `api/index.js` + `vercel.json`; mode serverless aktif otomatis (`VERCEL=1`): tanpa timer latar/warmer, tanpa migrate saat runtime. Butuh PostgreSQL terkelola (URL *pooled*, akhiri `?sslmode=verify-full`) dan Redis (`rediss://...`). Isi `DATABASE_URL`, `REDIS_URL`, `JWT_SECRET` (centang Production **dan Build**, karena build menjalankan migrate). Server biasa lebih cepat karena L1 cache, warmer, dan prefetch aktif.

## Endpoint (`/v1`)

| Endpoint | Fungsi |
|---|---|
| `GET /search?q&page&limit&provider` | cari di semua provider |
| `GET /latest?page&limit&provider&type` | update terbaru (`type` = comic/manga/novel) |
| `GET /popular?days=7&page&limit` | populer = paling banyak dibuka lewat API ini |
| `GET /recommended` | rating tertinggi, lalu terbaru |
| `GET /genres`, `GET /genres/:slug/series?sort&type&status` | genre & seri per genre (`sort`: latest/popular/rating/az) |
| `GET /series/:id`, `/series/:id/chapters` | detail + chapter |
| `GET /chapters/:id` | gambar chapter + `imageHeaders` (referer wajib untuk provider tertentu) |
| `GET /announcements` | pengumuman aktif (maks. 5) |
| `GET /providers`, `GET /health` | status provider/breaker, cek PostgreSQL & Redis |

Id unified: `<provider>:<id asli>` (`shinigami:<uuid>`, `lumos:<slug>`; chapter Lumos `lumos:<slug>:<chapter-slug>`). Sukses `{ data, meta }`, error `{ error: { code, message } }`.

### Akun, profil, komunitas
- **Login:** hanya Google (`POST /auth/google`, `GET /auth/me`, `GET /auth/config`); tanpa login = pengunjung (data hanya di perangkat). JWT HS256 90 hari. Isi `GOOGLE_CLIENT_ID` (client type *Web*; buat juga client *Android* untuk `id.mitoon.app` + SHA-1 keystore rilis yang tetap).
- **Profil:** `PATCH /auth/me` (`displayName` ≤30, `username` sekali per 7 hari → `429 USERNAME_COOLDOWN`, `bio` ≤150 & 3 baris); avatar JPEG ≤300 KB lewat `PUT/DELETE /auth/me/avatar`, publik `GET /users/:id/avatar?v=`; profil publik `GET /users/:id`; hapus akun `DELETE /auth/me?confirm=HAPUS`.
- **XP & level:** aturan di `src/core/xp.ts` (baca +10, komentar +5, pustaka +3, buka harian +5, disukai +1; batas harian WIB). App melapor `POST /xp/event {type: read|bookmark|daily, ref}`; peringkat mingguan `GET /xp/leaderboard`. **Streak membaca:** hari WIB tempat pengguna membaca ≥1 chapter dicatat di `read_days` (migrasi 030); `POST /xp/event` dan `GET /xp/streak` mengembalikan `{current, readToday}` (streak masih hidup bila kemarin membaca dan hari ini belum).
- **Komentar:** `GET/POST /comments` (target `s:<id seri>` / `c:<id chapter>`, ≤1000 karakter, 10/menit per IP), balasan 1 tingkat, vote `PUT /comments/:id/vote`, riwayat `GET /comments/mine`.
- **Global Chat:** `GET /chat` (publik, 50 pesan terakhir lama→baru), `POST /chat` (login, 1–500 karakter; `replyTo` opsional = id pesan yang dibalas, kutipan disimpan di kolom `reply_*` lewat migrasi `032_chat_reply.sql`), `DELETE /chat/:id` (milik sendiri; owner bebas). Tabel `chat_messages` otomatis dipangkas ke 50 baris tiap ada pesan baru (migrasi `031_chat.sql`).
- **Riwayat baca & pustaka:** `PUT /reading`, `GET /users/:id/reading`, `POST /library/sync` (maks. 1500 seri/akun).
- **Notifikasi:** `GET /notifications/unread` → `{ count, notif, info }`, `POST /notifications/read?kind=notif|info|all`.
- **Verified:** email di `OWNER_EMAILS` otomatis verified + akses admin saat masuk Google.

### Admin
Aktif bila `ADMIN_TOKEN` diisi (tidak tampil di `/docs`, 10 request/menit): pengumuman (`POST/DELETE /admin/announcements`, header `x-admin-token`) dan moderasi pengguna/komentar/seri untuk owner (`/admin/users`, `/admin/comments`, ...). Akun owner tidak bisa diblokir/dibisukan/dihapus. Paksa keluar sesi: `POST /admin/users/:id/logout` (satu user) dan `POST /admin/logout-all` (semua user; admin pemanggil tetap masuk, kirim `{"includeSelf":true}` untuk ikut keluar).

## Cara kerja singkat
- **Cache:** L1 di memori + Redis, stale-while-revalidate + singleflight; Redis mati = cache dilewati, API tetap jalan. Respons gzip + ETag + `Cache-Control`/`s-maxage`. Rate limit di memori (300/menit per IP).
- **Fallback:** list memanggil semua provider paralel dan hanya menunggu provider lambat `listGraceMs`; detail/chapter yang gagal dicari di provider lain (judul ternormalisasi, chapter dicocokkan lewat nomor). Circuit breaker per provider (5 gagal → buka 30 dtk). Tanpa Redis, detail dilayani dari PostgreSQL.
- **Warmer** (server biasa): mengisi cache/DB berkala, crawl katalog genre, prefetch chapter berikutnya.
- **Database:** `migrations/*.sql` transaksional dengan advisory lock; pool 10 (Vercel 2), `statement_timeout` 8 dtk. Jangan ubah nama/isi migration yang sudah dijalankan.
- **Shinigami:** domain situs & API ditulis langsung di `src/providers/shinigami.ts` (`BASE`, `API`).

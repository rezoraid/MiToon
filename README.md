# MiToon

Aplikasi baca komik (Jetpack Compose) untuk backend **Unified Scraper API**. Font Urbanist, ikon & logo dari aset MiToon.

## Build di GitHub (APK)
1. Push proyek ini ke repo GitHub.
2. **Settings → Secrets and variables → Actions → Variables → New repository variable**
   `API_BASE_URL` = URL backend, diakhiri `/` (mis. `https://api-kamu.vercel.app/`).
   Memakai `http://` (VPS tanpa SSL) juga bisa; cleartext otomatis diaktifkan.
3. Tab **Actions → Build APK → Run workflow** (atau cukup push ke `main`). APK ada di *Artifacts* → `MiToon-apk`.
4. Rilis: `git tag v1.0.0 && git push --tags` → APK otomatis dilampirkan ke **Releases**.

### Tanda tangan rilis (disarankan)
Tanpa keystore, APK ditandatangani debug key (tetap bisa dipasang, tapi update dari build dengan key lain harus uninstall dulu).
Untuk key tetap, buat sekali lalu simpan sebagai *Secrets*:
```bash
keytool -genkeypair -v -keystore mitoon.jks -alias mitoon -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 mitoon.jks        # macOS: base64 -i mitoon.jks
```
Secrets: `KEYSTORE_BASE64` (hasil base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`mitoon`), `KEY_PASSWORD`. **Simpan file `.jks` dengan aman** — kalau hilang, update tidak bisa menimpa instalasi lama.

## Build lokal
```bash
./gradlew assembleRelease -PAPI_BASE_URL=https://api-kamu.vercel.app/
```
Butuh JDK 17 dan Android SDK (platform 34). Hasil: `app/build/outputs/apk/release/app-release.apk`.

## Catatan
- Font: `app/src/main/res/font/urbanist_*.ttf` (instance statis 400–800 dari font variable Urbanist, lisensi SIL OFL).
- Ikon: `res/mipmap-*` (adaptive + legacy), logo header: `res/drawable-nodpi/logo.png`, `playstore-icon.png` (512 px).
- Warna dasar/aksen ada di `Ui.kt` (`Bg`, `CardBg`, `Accent`, `OnAccent`); tema saat ini hitam-putih (Accent = putih).
- Fitur yang dihapus bersama backend (migrasi 039): stiker, background header profil, foto profil GIF, dan layar pembelian Premium. Premium kini diberikan admin dan hanya membuka badge, bonus XP, serta tema dan frame avatar (menu Akun, hanya tampil untuk akun Premium).

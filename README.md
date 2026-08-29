# OakCraft Suite

Ek master app jisme team ke saare OakCraft apps ek jagah milte hain — **CRM · Stock · Attendance**.
Har user ko sirf wahi apps dikhte hain jinka access usko diya gaya hai, aur har app ka
**latest APK apne aap** us app ki GitHub repo ke Releases se aata hai (Install / Update / Open).

```
OakCraft Suite (yeh repo)                     App repos (unme kuch change nahi)
┌──────────────────────────────┐              ┌────────────────────────────────────┐
│ www/apps.json  ← catalogue   │──reads──────▶│ Oakcraft_Sales_CRM  → Releases/latest│
│ www/index.html ← launcher UI │              │ New_Stock_Software  → Releases/latest│
│ android/       ← APK shell   │              │ oakcraft_attendance → Releases/latest│
│ apps-script/   ← login + ACL │              └────────────────────────────────────┘
└──────────────────────────────┘
```

* Kisi bhi app repo me jab bhi naya APK release hota hai (Actions workflow), Suite me
  us app par **"Update available"** aa jaata hai — Suite ko dobara build karne ki zaroorat nahi.
* Naya app jodna ho to sirf `www/apps.json` me ek entry add karein — phones apne aap
  catalogue re-read karte hain (raw.githubusercontent.com se).
* Suite khud bhi self-update karti hai (apni Releases se).

## Repo layout

| Path | Kya hai |
|------|---------|
| `www/index.html` | Launcher UI (login → app tiles → Install / Update / Open, admin screen, self-update) |
| `www/apps.json` | **Catalogue** — apps ki list, unki repo, package name, icon; `backend` = login server URL |
| `www/icons/` | Tile icons |
| `apps-script/Suite.gs` | Google Apps Script backend: login, per-user app access (`Suite_Users` sheet), GitHub API cache |
| `android/` | WebView shell + installer bridge (`MainActivity.java`), APK version reader (`ApkInfo.java`), `build.sh` |
| `.github/workflows/suiteapk.yml` | Har push par Suite APK build → GitHub Release `apk-<run>` |
| `.github/workflows/unpackzip.yml` | Helper: repo root me `upload.zip` upload karo → files apne aap unpack + commit, phir APK build |

## Setup (ek baar)

1. **Secrets** (repo → Settings → Secrets and variables → Actions): `ANDROID_KEYSTORE_B64`,
   `ANDROID_KEYSTORE_PASS`, `ANDROID_KEY_ALIAS` — wahi values jo CRM repo me hain (same keystore theek hai).
2. **Login server**: `apps-script/Suite.gs` ke upar likhe 6 steps follow karein (naya Google Sheet →
   Apps Script → `setup` run → Web app deploy) → URL ko `www/apps.json` → `"backend"` me daal kar commit karein.
   Jab tak `backend` khali hai, app **open mode** me chalti hai (bina login, saare apps dikhte hain).
3. **Users**: Suite app me apne e-mail se sign in karein (pehli baar password banega) → menu →
   **Users & access** → team add karein aur har user ke apps tick karein. (Ya seedha Google Sheet
   ke `Suite_Users` tab me rows bharein: `Email | Name | Role | Apps | Status`.)
4. Team ko sirf **Suite APK** bhejein (Releases → latest → `OakCraft-Suite-*.apk`). Baaki apps
   wo Suite ke andar se install karenge.

## Version detection kaise hota hai

| App | Release style | Version kahan se |
|-----|---------------|------------------|
| CRM / Suite | `apk-<run>` releases, notes me `versionCode: N` | release notes se (exact) |
| Stock / Attendance | ek hi `latest` release, file overwrite | us build ke successful workflow run number se (`versionCode = run number`), `workflow` field in apps.json |

Phone par installed version `PackageManager` se padhi jaati hai; download ke baad APK ka
versionCode file se padh kar (`ApkInfo.java`) hi installer khulta hai, taaki purani file
kabhi "App not installed" na de.

## Build locally

```bash
sudo apt install android-sdk-build-tools android-sdk-platform-23 default-jdk   # Ubuntu/Debian
bash android/build.sh                                                            # debug key
KEYSTORE=/path/release.jks KEYSTORE_PASS='...' KEY_ALIAS=oakcraft bash android/build.sh
```

`www/index.html` ko browser me bhi khola ja sakta hai (web mode: tiles me "Download APK" link aata hai).
Har `<script>` plain ES2015 hai — edit ke baad `node --check` se syntax check karein.

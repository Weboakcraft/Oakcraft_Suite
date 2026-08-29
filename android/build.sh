#!/usr/bin/env bash
# ==============================================================================
#  OakCraft Suite — Android APK build (no Gradle / Android Studio needed)
#
#  Packs ../www (index.html, apps.json, icons) into the APK (assets/www) and compiles
#  the small WebView shell in src/.
#
#  Needs:  JDK 11+ (javac), Android build-tools (aapt, zipalign, apksigner, d8 or dx)
#          and one platform android.jar.  Works with:
#            - Ubuntu/Debian:  apt install android-sdk-build-tools android-sdk-platform-23
#            - Android Studio / GitHub Actions SDK ($ANDROID_HOME)
#
#  Usage:  bash android/build.sh                 -> android/build/OakCraft-Suite-<version>.apk (debug key)
#          KEYSTORE=release.jks KEYSTORE_PASS=xxx KEY_ALIAS=oakcraft bash android/build.sh   -> release-signed
#
#  Env overrides: ANDROID_HOME, BUILD_TOOLS (dir), ANDROID_JAR, VERSION_CODE, VERSION_NAME,
#                 KEYSTORE, KEYSTORE_PASS, KEY_PASS, KEY_ALIAS, OUT_DIR
# ==============================================================================
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}}"
if [ -z "${BUILD_TOOLS:-}" ]; then
  BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "${ANDROID_JAR:-}" ]; then
  ANDROID_JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -x "$BUILD_TOOLS/aapt" ] || { echo "aapt not found in $BUILD_TOOLS (set BUILD_TOOLS / ANDROID_HOME)"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found (set ANDROID_JAR)"; exit 1; }

VERSION_NAME="${VERSION_NAME:-1.0.$(date +%Y%m%d)}"
VERSION_CODE="${VERSION_CODE:-$(date +%y%m%d%H)}"     # yyMMddHH -> always increasing, fits in int
OUT="${OUT_DIR:-build}"
JAVA_TOOL_OPTIONS="" ; export JAVA_TOOL_OPTIONS

echo "== OakCraft Suite APK  v$VERSION_NAME ($VERSION_CODE)"
echo "   build-tools: $BUILD_TOOLS"
echo "   android.jar: $ANDROID_JAR"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/assets/www"

# 1) web UI + catalogue -> assets/www
cp -r ../www/. "$OUT/assets/www/"
if command -v python3 >/dev/null 2>&1; then
  python3 -c "import json,sys; json.load(open(sys.argv[1], encoding='utf-8'))" "$OUT/assets/www/apps.json" \
    || { echo "www/apps.json is not valid JSON — fix it before building"; exit 1; }
fi
echo "   assets: $(du -sh "$OUT/assets/www" | cut -f1)"

# 2) resources + manifest -> R.java + unsigned apk   (version stamped into a temp manifest)
sed -e "s/__VERSION_CODE__/$VERSION_CODE/" -e "s/__VERSION_NAME__/$VERSION_NAME/" AndroidManifest.xml > "$OUT/AndroidManifest.xml"
"$BUILD_TOOLS/aapt" package -f -m \
  -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S res -A "$OUT/assets" -I "$ANDROID_JAR" \
  -F "$OUT/app.unsigned.apk"

# 3) java -> class (Java 8 bytecode so both dx and d8 accept it)
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/obj" \
  $(find src -name '*.java') $(find "$OUT/gen" -name '*.java')

# 4) class -> classes.dex
if [ -x "$BUILD_TOOLS/d8" ]; then
  "$BUILD_TOOLS/d8" --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/obj" -name '*.class')
elif [ -x "$BUILD_TOOLS/dx" ]; then
  "$BUILD_TOOLS/dx" --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/obj"
elif command -v dalvik-exchange >/dev/null 2>&1; then
  dalvik-exchange --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/obj"
else
  echo "neither d8 nor dx found"; exit 1
fi

# 5) dex into apk, align
( cd "$OUT" && "$BUILD_TOOLS/aapt" add app.unsigned.apk classes.dex >/dev/null )
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"

# 6) sign (release keystore if given, else a local debug keystore)
KS="${KEYSTORE:-}"
if [ -z "$KS" ]; then
  mkdir -p .local
  KS=".local/debug.jks"; KEYSTORE_PASS="${KEYSTORE_PASS:-android}"; KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OakCraft Suite debug" >/dev/null 2>&1
  fi
  echo "   signing: DEBUG key ($KS) — set KEYSTORE=... for a release build"
else
  echo "   signing: $KS (alias ${KEY_ALIAS:-oakcraft})"
fi
KEY_PASS="${KEY_PASS:-${KEYSTORE_PASS:-}}"
APK="$OUT/OakCraft-Suite-$VERSION_NAME.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass "pass:${KEYSTORE_PASS:-android}" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "${KEY_ALIAS:-oakcraft}" --out "$APK" "$OUT/app.aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | sed 's/^/   /' | head -4
rm -f "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
echo "== DONE: $APK ($(du -h "$APK" | cut -f1))"

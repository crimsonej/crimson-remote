#!/usr/bin/env bash
# Builds Crimson Remote without Gradle, using the Android build tools directly.
# Needs: JDK 11+, Android build-tools (aapt2, d8, zipalign, apksigner) and a platform android.jar.
#   ANDROID_JAR=/path/to/android.jar BUILD_TOOLS=/path/to/build-tools/35.0.0 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
BT="${BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/35.0.0}"
JAR="${ANDROID_JAR:?set ANDROID_JAR to a platform android.jar (API 34)}"
SRC=app/src/main
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
# The web app (web/) is bundled so the phone can serve it to an iPhone.
mkdir -p "$OUT/assets/web" && cp -r web/. "$OUT/assets/web/"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$SRC/AndroidManifest.xml" "$OUT/res.zip" -A "$OUT/assets" --java "$OUT/gen" --auto-add-overlay
javac --release 11 -nowarn -cp "$JAR" -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 24 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="${KEYSTORE:-release.jks}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias crimson -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "${KS_PASS:-crimsonpass}" -keypass "${KS_PASS:-crimsonpass}" -dname "CN=Crimson" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:${KS_PASS:-crimsonpass}" --out "$OUT/CrimsonRemote.apk" "$OUT/aligned.apk" 2>/dev/null
echo "Built $OUT/CrimsonRemote.apk"

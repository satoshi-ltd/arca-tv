#!/usr/bin/env bash
# Builds arca TV's APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK command-line tools (build-tools 34+, platform android-34). See README.md.
set -euo pipefail
cd "$(dirname "$0")"

# --- Tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools \
           /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }

# --- Settings: config.yaml -> Config.java
legacy() { grep -E "^[[:space:]]*$1[[:space:]]*=" config.properties | tail -1 | cut -d= -f2- | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true; }
if [ ! -f config.yaml ] && [ -f config.properties ]; then
  {
    printf 'city: "%s"\n\njellyfin:\n  url: "%s"\n  token: "%s"\n\n' "$(legacy city)" "$(legacy jellyfin_url)" "$(legacy jellyfin_token)"
    printf 'domo:\n  url: "%s"\n  token: "%s"\n' "$(legacy domo_url)" "$(legacy domo_token)"
  } > config.yaml
  mv config.properties config.properties.bak
  echo "Moved your settings from config.properties to config.yaml (the old file is config.properties.bak)."
fi
if [ ! -f config.yaml ]; then
  cp config.example.yaml config.yaml
  echo "Created config.yaml: add your city there (and, if you use them, your Jellyfin server and key, or your domo server address and key for arca house), then build again."
fi
cfg() {
  local v
  v="$(awk -v want="$1" '
    /^[[:space:]]*(#|$)/ { next }
    {
      line = $0
      indent = match(line, /[^ ]/) - 1
      sub(/^[[:space:]]+/, "", line)
      i = index(line, ":")
      if (i == 0) next
      k = substr(line, 1, i - 1)
      v = substr(line, i + 1)
      sub(/^[[:space:]]+/, "", v)
      sub(/[[:space:]]+#.*$/, "", v)
      sub(/[[:space:]]+$/, "", v)
      if (indent == 0) { section = (v == "") ? k : ""; path = k } else { path = section "." k }
      if (path == want && v != "") { gsub(/^["\047]|["\047]$/, "", v); out = v }
    }
    END { printf "%s", out }' config.yaml)"
  v="$(printf '%s' "$v" | tr -d '"\\')"
  printf '%s' "${v:-$2}"
}
rm -rf out && mkdir -p out/gen/com/satoshilimited/tv out/classes
cat > out/gen/com/satoshilimited/tv/Config.java <<EOF
package com.satoshilimited.tv;

final class Config {
    private Config() {}

    static final String CITY = "$(cfg city '')";
    static final String JELLYFIN_URL = "$(cfg jellyfin.url '')";
    static final String JELLYFIN_TOKEN = "$(cfg jellyfin.token '')";
    static final String DOMO_URL = "$(cfg domo.url '')";
    static final String DOMO_TOKEN = "$(cfg domo.token '')";
}
EOF

# --- Build
"$BT/aapt2" compile --dir app/res -o out/res.zip
# Fonts uncompressed: Android reads them straight from the APK.
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 28 --target-sdk-version 33 -A app/assets -0 ttf
# Windows aapt2 packs assets with backslash separators ("assets/symbols\x.png") and Android
# cannot find them: the home screen loses its icons and fonts. Rename the zip entries to "/".
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    cat > out/FixAssets.java <<'EOF'
import java.nio.file.*;
import java.util.zip.*;

class FixAssets {
    public static void main(String[] args) throws Exception {
        Path apk = Paths.get(args[0]);
        Path tmp = apk.resolveSibling(apk.getFileName() + ".fixed");
        int renamed = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(apk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                String name = e.getName().replace('\\', '/');
                if (!name.equals(e.getName())) renamed++;
                ZipEntry n = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) { // Android reads fonts straight from the APK
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(data.length);
                    n.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
        }
        if (renamed > 0) Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        else Files.delete(tmp);
        System.out.println("FixAssets: renamed " + renamed + " asset paths in " + apk.getFileName());
    }
}
EOF
    "${JAVA_HOME:+$JAVA_HOME/bin/}java" out/FixAssets.java out/base.apk
    ;;
esac
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src out/gen -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# --- Sign with your own key (created once; updates need the same key, so keep arca-tv.keystore)
if [ ! -f arca-tv.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore arca-tv.keystore -alias arcatv -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass arcatv -keypass arcatv -dname "CN=arca TV" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks arca-tv.keystore --ks-pass pass:arcatv --key-pass pass:arcatv --out ArcaTV.apk out/aligned.apk
echo "Built ArcaTV.apk ($(du -h ArcaTV.apk | cut -f1)). Install: adb install -r ArcaTV.apk"

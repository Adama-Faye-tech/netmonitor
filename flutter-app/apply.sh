#!/usr/bin/env bash
# Usage : ./apply.sh /chemin/vers/projet_flutter
# Injecte l'UI Dart, le pont natif et le service Kotlin (partagé avec ../kotlin-app)
# dans un projet créé par « flutter create ».
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:?Usage: ./apply.sh /chemin/vers/projet_flutter}"
SHARED="$HERE/../kotlin-app/app/src/main/java/com/netmonitor/app"
NATIVE="$TARGET/android/app/src/main/kotlin/com/netmonitor/app"

[ -d "$TARGET/android/app" ] || { echo "Projet Flutter introuvable dans $TARGET"; exit 1; }

cp "$HERE/overlay/pubspec.yaml" "$TARGET/pubspec.yaml"
cp "$HERE/overlay/lib/main.dart" "$TARGET/lib/main.dart"
mkdir -p "$TARGET/assets"
cp "$HERE/overlay/assets/netmonitor_background.jpg" "$TARGET/assets/"
rm -rf "$TARGET/test"

mkdir -p "$NATIVE" "$TARGET/android/app/src/main/res/drawable" \
  "$TARGET/android/app/src/main/res/values" "$TARGET/android/app/src/main/res/values-v31"
cp "$HERE/overlay/android/app/src/main/AndroidManifest.xml" "$TARGET/android/app/src/main/AndroidManifest.xml"
cp "$HERE/overlay/android/app/src/main/res/drawable/ic_stat_signal.xml" "$TARGET/android/app/src/main/res/drawable/"
cp "$HERE/overlay/android/app/src/main/res/drawable/ic_splash_netmonitor.xml" "$TARGET/android/app/src/main/res/drawable/"
cp "$HERE/overlay/android/app/src/main/res/values/styles.xml" "$TARGET/android/app/src/main/res/values/"
cp "$HERE/overlay/android/app/src/main/res/values-v31/styles.xml" "$TARGET/android/app/src/main/res/values-v31/"

# Service, sondes, évaluateur… : mêmes fichiers que la version Kotlin (sauf son MainActivity).
for f in "$SHARED"/*.kt; do
  [ "$(basename "$f")" = "MainActivity.kt" ] || cp "$f" "$NATIVE/"
done
cp "$HERE/overlay/android/app/src/main/kotlin/com/netmonitor/app/MainActivity.kt" "$NATIVE/MainActivity.kt"

GRADLE="$TARGET/android/app/build.gradle.kts"
[ -f "$GRADLE" ] || GRADLE="$TARGET/android/app/build.gradle"

# minSdk 24 requis (registerDefaultNetworkCallback)
sed -i -E 's/flutter\.minSdkVersion/24/' "$GRADLE"

if ! grep -q "kotlinx-coroutines-android" "$GRADLE"; then
  cat >> "$GRADLE" <<'GRADLE_DEPS'

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
GRADLE_DEPS
fi

echo "OK : lancez maintenant « flutter run » ou « flutter build apk --release » dans $TARGET"

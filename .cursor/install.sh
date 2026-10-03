#!/usr/bin/env bash
# Cloud Agent install script for AndroidAPS (AAPS).
#
# It prepares an Android build toolchain on a fresh Linux VM:
#   - points JAVA_HOME at the JDK 21 that ships in the base image
#   - installs the Android SDK (command line tools, platform, build tools)
#   - writes local.properties so Gradle/AGP can find the SDK
#   - writes a home gradle.properties that fits this small VM
#
# It is safe to run more than once: every step checks for existing state
# first, and sdkmanager itself skips packages that are already installed.
set -euo pipefail

JAVA_HOME_DIR="/usr/lib/jvm/java-21-openjdk-amd64"
ANDROID_SDK_DIR="$HOME/android-sdk"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"

# The three SDK packages the build needs. compileSdk / build tools come from
# buildSrc/src/main/kotlin/Versions.kt (compileSdk = 37). Keep them in step.
SDK_PLATFORM="platforms;android-37.0"
SDK_BUILD_TOOLS="build-tools;37.0.0"

export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"

echo "==> Using JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version

# 1. Android command line tools -------------------------------------------------
SDKMANAGER="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "==> Installing Android command line tools"
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
  tmp_zip="$(mktemp --suffix=.zip)"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp_zip"
  rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest" "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools"
  unzip -q -o "$tmp_zip" -d "$ANDROID_SDK_DIR/cmdline-tools"
  mv "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
  rm -f "$tmp_zip"
else
  echo "==> Android command line tools already present"
fi

# 2. SDK packages + licenses ----------------------------------------------------
echo "==> Accepting SDK licenses"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
echo "==> Installing SDK packages (platform-tools, $SDK_PLATFORM, $SDK_BUILD_TOOLS)"
"$SDKMANAGER" "platform-tools" "$SDK_PLATFORM" "$SDK_BUILD_TOOLS"

# 3. local.properties so AGP finds the SDK from any working directory -----------
echo "sdk.dir=$ANDROID_SDK_DIR" > /workspace/local.properties
echo "==> Wrote /workspace/local.properties"

# 4. Home gradle.properties: fit the build to this VM ---------------------------
# The committed gradle.properties targets a big CI runner (-Xmx8g, 12 workers).
# A home gradle.properties overrides org.gradle.* for this machine only and does
# not change anything in the repository.
mkdir -p "$HOME/.gradle"
cat > "$HOME/.gradle/gradle.properties" <<'PROPS'
# Cloud Agent VM tuning (4 cores / ~15Gi). Overrides the project values, which
# target a large CI runner, for this machine only. Not part of the repository.
org.gradle.jvmargs=-Xmx5g -XX:+UseParallelGC -Xss4m
org.gradle.workers.max=4
kotlin.daemon.jvm.options=-Xmx3g
org.gradle.daemon=false
PROPS
echo "==> Wrote $HOME/.gradle/gradle.properties"

# 5. Make the toolchain visible to future interactive shells --------------------
cat > "$HOME/.aaps_env.sh" <<EOF
export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$JAVA_HOME/bin:\$PATH"
EOF
for profile in "$HOME/.bashrc" "$HOME/.profile"; do
  touch "$profile"
  grep -q 'aaps_env.sh' "$profile" || echo '[ -f "$HOME/.aaps_env.sh" ] && . "$HOME/.aaps_env.sh"' >> "$profile"
done
echo "==> Environment ready"

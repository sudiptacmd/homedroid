# Source from bash or zsh: source tools/dev.sh
# Selects the project-local tools that tools/setup-dev.sh installed into .tools/.
_hd_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
_hd_java="$(ls -d "$_hd_root"/.tools/java/*/ 2>/dev/null | head -1)"
if [ -z "$_hd_java" ]; then
  echo "No tools yet: run tools/setup-dev.sh first" >&2
else
  [ -d "$_hd_java/Contents/Home" ] && _hd_java="$_hd_java/Contents/Home"
  export JAVA_HOME="${_hd_java%/}"
  export ANDROID_HOME="$_hd_root/.tools/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
  export ANDROID_AVD_HOME="$_hd_root/.tools/avd"
  export GRADLE_USER_HOME="$_hd_root/.tools/gradle"
  export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$_hd_root/.tools/go/bin:$PATH"
  echo "Homedroid tools ready: $(java -version 2>&1 | head -1), Android SDK at $ANDROID_HOME"
fi
unset _hd_root _hd_java

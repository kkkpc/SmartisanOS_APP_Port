#!/usr/bin/env bash
#
# Java -> dex -> smali 合入管道
#
# 用途：把 src/ 下的新增 Java 源码编译成 smali，合并进 apktool/calendar/smali/，
#       之后即可用 make sign 回编译出带新功能的 APK。
#
# 依赖（全部在 Tool/lib/，随仓库走）：
#   r8.jar        -> d8 编译器（com.android.tools.r8.R8）
#   baksmali.jar  -> dex -> smali
#
# 步骤：
#   1. javac --release 8  编译 src/**/*.java -> build/classes
#   2. d8                  class -> build/dex/classes.dex
#   3. baksmali            dex -> build/smali
#   4. 合并                build/smali/** -> apktool/calendar/smali/
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAVA="${JAVA_HOME:-/opt/homebrew/opt/openjdk}/bin/java"
JAVAC="${JAVA_HOME:-/opt/homebrew/opt/openjdk}/bin/javac"
LIB="Tool/lib"
SRC="src"
BUILD="build"
CAL_SMALI="apktool/calendar/smali"

R8_JAR="$LIB/r8.jar"
SMALI_CP="$LIB/baksmali.jar:$LIB/util-2.5.2.jar:$LIB/jcommander-1.72.jar:$LIB/guava-27.1-jre.jar:$LIB/dexlib2-2.5.2.jar"

echo "==> [1/4] 清理 build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/smali"

echo "==> [2/4] javac 编译 $SRC"
# -source 8 -target 8：产出与宿主 APK（minSdk 27 / targetSdk 34）兼容的字节码
# classpath 用 Android API 35 的 android.jar（向下兼容 targetSdk 34）
"$JAVAC" --release 8 -nowarn -cp "$LIB/android.jar" -d "$BUILD/classes" $(find "$SRC" -name "*.java")

echo "==> [3/4] d8 转 dex"
# --min-api 27 对齐宿主 APK；--classpath 提供 android.jar 供 desugar 解析
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api 27 \
    --classpath "$LIB/android.jar" \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name "*.class")

echo "==> [4/4] baksmali 转 smali 并合入"
"$JAVA" -cp "$SMALI_CP" org.jf.baksmali.Main d "$BUILD/dex/classes.dex" -o "$BUILD/smali"

# 合并进 apktool 工程（覆盖同名文件）
count=0
while IFS= read -r -d '' f; do
  rel="${f#$BUILD/smali/}"
  dest="$CAL_SMALI/$rel"
  mkdir -p "$(dirname "$dest")"
  cp "$f" "$dest"
  count=$((count + 1))
done < <(find "$BUILD/smali" -name "*.smali" -print0)

echo "==> 完成：$count 个 smali 文件已合入 $CAL_SMALI"
echo "    新增类："
grep -rhoE "^\.class .*L(dbg/[A-Za-z0-9_]+);" "$BUILD/smali" 2>/dev/null | sed 's/^/      /' || true

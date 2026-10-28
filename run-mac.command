#!/bin/bash
# macOS 啟動腳本：在 Finder 雙擊即可執行（第一次請在終端機執行 chmod +x run-mac.command）
# 會自動尋找 Java 17 以上的 JDK；系統預設是 Java 8 也沒關係，不會改動預設值。
cd "$(dirname "$0")" || exit 1

java_major() {   # 傳入 java 執行檔路徑，輸出主版本號（1.8 → 8）
  "$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1=="1") print $2; else print $1 }'
}

JAVA_BIN=""
# 0) 放在本資料夾的 JDK（jdk/、jdk-21.x/，含 macOS 的 Contents/Home 結構）
for cand in ./jdk/bin/java ./jdk/Contents/Home/bin/java ./jdk*/bin/java ./jdk*/Contents/Home/bin/java ./jdk/*/bin/java ./jdk/*/Contents/Home/bin/java; do
  if [ -z "$JAVA_BIN" ] && [ -x "$cand" ]; then JAVA_BIN="$(cd "$(dirname "$cand")" && pwd)/java"; fi
done
# 1) 使用者自訂：export SECNEWS_JAVA=/路徑/bin/java
if [ -z "$JAVA_BIN" ] && [ -n "$SECNEWS_JAVA" ] && [ -x "$SECNEWS_JAVA" ]; then JAVA_BIN="$SECNEWS_JAVA"; fi
# 2) macOS 已安裝的 JDK 中找 17 以上
if [ -z "$JAVA_BIN" ]; then
  JH=$(/usr/libexec/java_home -v 17+ 2>/dev/null)
  [ -n "$JH" ] && [ -x "$JH/bin/java" ] && JAVA_BIN="$JH/bin/java"
fi
# 3) SDKMAN 安裝的版本
if [ -z "$JAVA_BIN" ] && [ -d "$HOME/.sdkman/candidates/java" ]; then
  for d in $(ls -1d "$HOME"/.sdkman/candidates/java/*/ 2>/dev/null | sort -r); do
    if [ -x "${d}bin/java" ] && [ "$(java_major "${d}bin/java")" -ge 17 ] 2>/dev/null; then JAVA_BIN="${d}bin/java"; break; fi
  done
fi
# 4) Homebrew 的 openjdk
if [ -z "$JAVA_BIN" ]; then
  for d in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@21 /usr/local/opt/openjdk@17 /usr/local/opt/openjdk; do
    if [ -x "$d/bin/java" ]; then JAVA_BIN="$d/bin/java"; break; fi
  done
fi
# 5) PATH 上的 java（版本不足時，程式會自己跳出提示視窗）
[ -z "$JAVA_BIN" ] && JAVA_BIN="$(command -v java)"

if [ -z "$JAVA_BIN" ]; then
  osascript -e 'display alert "找不到 Java" message "請安裝 JDK 17 或 21（可與現有的 Java 8 並存）。"'
  exit 1
fi
echo "使用 Java：$JAVA_BIN（版本 $(java_major "$JAVA_BIN")）"

# 低資源設定：小 heap、Serial GC、只用 C1 編譯器
nohup "$JAVA_BIN" -Xms16m -Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k \
  -Dfile.encoding=UTF-8 -jar SecNewsMonitor.jar >/dev/null 2>&1 &

# 關閉這個終端機視窗（程式會在背景繼續執行）
osascript -e 'tell application "Terminal" to close (every window whose name contains "run-mac.command")' >/dev/null 2>&1 &
exit 0

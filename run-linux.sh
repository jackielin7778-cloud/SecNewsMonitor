#!/bin/bash
# Linux 啟動腳本。
#   ./run-linux.sh               有圖形介面（桌面版 Linux）時開啟視窗
#   ./run-linux.sh --setup       第一次使用：建立帳號、設定 Webhook（伺服器）
#   ./run-linux.sh --headless    無介面模式常駐執行（伺服器／systemd）
#   ./run-linux.sh --help        所有指令
#
# 尋找 Java 17 以上的順序：本資料夾內的 JDK（jdk/、jdk-21.x/）→ SECNEWS_JAVA → JAVA_HOME → PATH 上的 java
# 不會修改系統的 Java 設定。
set -u
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
cd "$DIR" || exit 1

java_major() {   # 傳入 java 執行檔路徑，輸出主版本號（1.8 → 8）
  "$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1=="1") print $2; else print $1 }'
}
usable() { [ -x "$1" ] && [ "$(java_major "$1" 2>/dev/null || echo 0)" -ge 17 ] 2>/dev/null; }

JAVA_BIN=""
for cand in "$DIR"/jdk/bin/java "$DIR"/jdk*/bin/java "$DIR"/jdk/*/bin/java; do
  if [ -z "$JAVA_BIN" ] && usable "$cand"; then JAVA_BIN="$cand"; fi
done
if [ -z "$JAVA_BIN" ] && [ -n "${SECNEWS_JAVA:-}" ] && usable "$SECNEWS_JAVA"; then JAVA_BIN="$SECNEWS_JAVA"; fi
if [ -z "$JAVA_BIN" ] && [ -n "${JAVA_HOME:-}" ] && usable "$JAVA_HOME/bin/java"; then JAVA_BIN="$JAVA_HOME/bin/java"; fi
if [ -z "$JAVA_BIN" ] && command -v java >/dev/null 2>&1 && usable "$(command -v java)"; then JAVA_BIN="$(command -v java)"; fi

if [ -z "$JAVA_BIN" ]; then
  echo "找不到 Java 17 以上。請把 JDK（例如 Temurin 21 的 Linux x64 .tar.gz）解壓縮到：" >&2
  echo "  $DIR/jdk-21.x.x/   （確認 $DIR/jdk-21.x.x/bin/java 存在）" >&2
  echo "或設定環境變數 SECNEWS_JAVA=/路徑/bin/java" >&2
  exit 1
fi

# 低資源設定：小 heap、Serial GC、只用 C1 編譯器
JAVA_OPTS=(-Xms16m -Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8)

if [ $# -eq 0 ] && { [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; }; then
  # 桌面環境：圖形介面放到背景執行
  nohup "$JAVA_BIN" "${JAVA_OPTS[@]}" -jar "$DIR/SecNewsMonitor.jar" >/dev/null 2>&1 &
  echo "已啟動 SecNewsMonitor（Java：$JAVA_BIN）"
  exit 0
fi

# 命令列／無介面模式：前景執行（exec 讓 systemd 直接管理 Java 程序與停止訊號）
exec "$JAVA_BIN" "${JAVA_OPTS[@]}" -jar "$DIR/SecNewsMonitor.jar" "$@"

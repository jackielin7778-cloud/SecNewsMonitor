#!/bin/bash
# 資安靜態掃描（macOS / Linux）：SpotBugs + Find Security Bugs 掃描 SecNewsMonitor.jar。
# 第一次執行會下載工具到 tools/（約 15 MB，需要網路），之後離線即可。
# 報告：build/security-scan.html（用瀏覽器開啟）、build/security-scan.xml
# 用法：./security-scan.sh            掃描資料夾中的 SecNewsMonitor.jar（沒有就先執行 build.sh）
set -e
cd "$(dirname "$0")"

SB_VER=4.9.2
SB_SHA256=2ac6f163c266d2f7c11cc89d80d07ccad6755ac468cee20ca0b1e4217e567548   # GitHub Release 公布的 SHA-256
FSB_VER=1.14.0
SB_URL="https://github.com/spotbugs/spotbugs/releases/download/$SB_VER/spotbugs-$SB_VER.zip"
FSB_URL="https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/$FSB_VER/findsecbugs-plugin-$FSB_VER.jar"
TOOLS=tools
SB_DIR="$TOOLS/spotbugs-$SB_VER"
FSB_JAR="$TOOLS/findsecbugs-plugin-$FSB_VER.jar"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
sha1()   { if command -v sha1sum   >/dev/null; then sha1sum   "$1" | cut -d' ' -f1; else shasum -a 1   "$1" | cut -d' ' -f1; fi; }

# ---- Java：程式資料夾內的 jdk*，否則 PATH 上的 java ----
JAVA=""
for d in jdk*/ jdk*/Contents/Home/; do [ -x "${d}bin/java" ] && JAVA="${d}bin/java"; done
[ -z "$JAVA" ] && JAVA="$(command -v java || true)"
[ -z "$JAVA" ] && { echo "找不到 Java（需要 Java 11 以上）"; exit 1; }

# ---- 下載工具（只有第一次）----
mkdir -p "$TOOLS"
if [ ! -f "$SB_DIR/lib/spotbugs.jar" ]; then
  echo "下載 SpotBugs $SB_VER …"
  curl -fL --retry 2 -o "$TOOLS/spotbugs.zip" "$SB_URL"
  got=$(sha256 "$TOOLS/spotbugs.zip")
  if [ "$got" != "$SB_SHA256" ]; then
    echo "錯誤：SpotBugs 檔案的 SHA-256 不符（$got），已刪除，不執行。"; rm -f "$TOOLS/spotbugs.zip"; exit 1
  fi
  (cd "$TOOLS" && unzip -q -o spotbugs.zip && rm spotbugs.zip)
fi
if [ ! -f "$FSB_JAR" ]; then
  echo "下載 Find Security Bugs $FSB_VER …"
  curl -fL --retry 2 -o "$FSB_JAR.tmp" "$FSB_URL"
  want=$(curl -fsSL "$FSB_URL.sha1" | cut -c1-40)
  got=$(sha1 "$FSB_JAR.tmp")
  if [ "$got" != "$want" ]; then
    echo "錯誤：Find Security Bugs 檔案的 SHA-1 不符，已刪除，不執行。"; rm -f "$FSB_JAR.tmp"; exit 1
  fi
  mv "$FSB_JAR.tmp" "$FSB_JAR"
fi

# ---- 掃描 ----
[ -f SecNewsMonitor.jar ] || ./build.sh
mkdir -p build
echo "掃描 SecNewsMonitor.jar …"
"$JAVA" -Xmx1g -jar "$SB_DIR/lib/spotbugs.jar" -textui -effort:max -low \
  -pluginList "$FSB_JAR" -onlyAnalyze com.secnews.- \
  -html=build/security-scan.html -xml:withMessages=build/security-scan.xml SecNewsMonitor.jar

total=$(grep -o '<BugInstance ' build/security-scan.xml | wc -l | tr -d ' ')
sec=$(grep -o '<BugInstance [^>]*category="SECURITY"' build/security-scan.xml | wc -l | tr -d ' ')
echo
echo "完成：共 $total 項（其中資安類 $sec 項）"
echo "報告：$(pwd)/build/security-scan.html"
echo "提醒：靜態掃描會有誤報，請逐項判斷；把報告交給審查（security-review 技能）一起確認。"

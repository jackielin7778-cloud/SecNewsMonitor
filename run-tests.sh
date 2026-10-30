#!/bin/bash
# 執行自動測試（macOS / Linux）。需要 JDK 17 以上。不需網路、不需圖形介面。
set -e
set -o pipefail
cd "$(dirname "$0")"
rm -rf build/test-classes build/classes && mkdir -p build/classes build/test-classes
javac --release 8 -encoding UTF-8 -d build/classes src/com/secnews/Launcher.java 2>/dev/null
javac --release 17 -encoding UTF-8 -d build/classes $(find src -name '*.java' ! -name Launcher.java)
javac --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes $(find test -name '*.java')
FAILED=0
for t in SelfTest V11Test V12Test V13Test V14Test V15Test V16Test V17Test V171Test ResetTest; do
  echo "== $t"
  timeout 300 java -Dsecnews.allowPrivate=true -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -Xmx96m -cp build/classes:build/test-classes com.secnews.$t | grep -v '^snippet=' || FAILED=1
done
if [ $FAILED -ne 0 ]; then echo "測試失敗"; exit 1; fi
echo "全部測試通過"

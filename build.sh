#!/bin/bash
# 從原始碼重新編譯（macOS / Linux）。需要 JDK 17 以上。
# Launcher 以 Java 8 相容格式編譯，好讓舊版 Java 也能顯示「版本不足」提示。
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes
javac --release 8 -encoding UTF-8 -d build/classes src/com/secnews/Launcher.java
javac --release 17 -encoding UTF-8 -d build/classes $(find src -name '*.java' ! -name Launcher.java)
printf 'Main-Class: com.secnews.Launcher\nImplementation-Version: 1.4.1\n' > build/MANIFEST.MF
jar --create --file SecNewsMonitor.jar --manifest build/MANIFEST.MF -C build/classes .
echo "完成：SecNewsMonitor.jar"

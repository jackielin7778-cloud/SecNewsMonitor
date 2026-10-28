#!/bin/bash
# Linux 命令列／無介面模式整合測試。需先執行 ./build.sh 產生 SecNewsMonitor.jar。
# 不需網路：使用本機 HTTP 伺服器（python3）提供測試用 RSS。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
export SECNEWS_HOME="$WORK/data"
unset DISPLAY WAYLAND_DISPLAY SECNEWS_PASSWORD SECNEWS_PASSWORD_FILE CREDENTIALS_DIRECTORY
RUN="$ROOT/run-linux.sh"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); }
bad()  { FAIL=$((FAIL+1)); echo "FAIL: $1"; }
check(){ if eval "$2"; then ok; else bad "$1"; fi; }
run()  { "$RUN" "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'; return "${PIPESTATUS[0]}"; }

PW='Passw0rd1'
HOOK='https://chat.googleapis.com/v1/spaces/TEST/messages?key=k&token=t'

# ---- 本機 RSS ----
mkdir -p "$WORK/www"
cat > "$WORK/www/feed.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?><rss><channel>
<item><title>Apache Tomcat 重大漏洞</title><link>http://127.0.0.1/x/1</link></item>
</channel></rss>
EOF
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
(cd "$WORK/www" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1) &
HTTP_PID=$!
sleep 1

# 1. 版本、說明
out=$(run --version); check "version" '[[ "$out" == *"SecNewsMonitor"* ]]'
out=$(run --help); rc=$?; check "help rc0" '[ $rc -eq 0 ] && [[ "$out" == *"--headless"* ]]'
out=$(run --bogus); rc=$?; check "unknown arg rc2" '[ $rc -eq 2 ]'

# 2. 沒有 DISPLAY 又沒帶參數 → 提示改用無介面模式
out=$(run); rc=$?; check "no display rc2" '[ $rc -eq 2 ] && [[ "$out" == *"--headless"* ]]'

# 3. 尚未建立帳號
out=$(run --status); check "status before setup" '[[ "$out" == *"尚未建立"* ]]'
out=$(SECNEWS_PASSWORD=$PW run --run-once); rc=$?; check "run-once without account rc1" '[ $rc -eq 1 ] && [[ "$out" == *"--setup"* ]]'

# 4. --setup（管線輸入：帳號、密碼、確認、Webhook）
out=$(printf 'ops\nshort\nshort\n%s\n%s\n%s\n' "$PW" "$PW" "$HOOK" | run --setup); rc=$?
check "setup rc0" '[ $rc -eq 0 ]'
check "setup rejects weak pw" '[[ "$out" == *"至少 8 碼"* ]]'
check "setup saved webhook" '[[ "$out" == *"Webhook 已加密儲存"* ]]'
check "data dir 700" '[ "$(stat -c %a "$SECNEWS_HOME")" = "700" ]'
check "webhook not plain" '! grep -q "token=t" "$SECNEWS_HOME/config.properties"'
out=$(printf 'x\n' | run --setup); rc=$?; check "setup twice rc1" '[ $rc -eq 1 ] && [[ "$out" == *"--reset"* ]]'

out=$(run --status); check "status after setup" '[[ "$out" == *"ops"* ]] && [[ "$out" == *"已設定"* ]] && [[ "$out" == *"未執行"* ]]'

# 測試用設定：只有本機 RSS、關鍵字 Tomcat、關閉推播（測試環境無法連 Google）
printf '# name\turl\ttype\tenabled\tfullText\nLocal\thttp://127.0.0.1:%s/feed.xml\tRSS\ttrue\tfalse\n' "$PORT" > "$SECNEWS_HOME/sites.tsv"
printf 'Tomcat\n' > "$SECNEWS_HOME/keywords.txt"
sed -i 's/^chat.enabled=.*//' "$SECNEWS_HOME/config.properties"; echo 'chat.enabled=false' >> "$SECNEWS_HOME/config.properties"

# 5. 密碼來源
out=$(SECNEWS_PASSWORD=wrong123 run --run-once); rc=$?; check "wrong password rc1" '[ $rc -eq 1 ] && [[ "$out" == *"密碼錯誤"* ]]'
printf '%s\n' "$PW" > "$WORK/pw"; chmod 644 "$WORK/pw"
out=$(run --run-once --password-file "$WORK/pw"); rc=$?
check "password-file works" '[ $rc -eq 0 ] && [[ "$out" == *"已建立基準"* ]]'
check "password-file perm warning" '[[ "$out" == *"chmod 600"* ]]'
chmod 600 "$WORK/pw"
# 新文章 → 第二次執行應命中
cat > "$WORK/www/feed.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?><rss><channel>
<item><title>Apache Tomcat 重大漏洞</title><link>http://127.0.0.1/x/1</link></item>
<item><title>Tomcat 9 緊急更新</title><link>http://127.0.0.1/x/2</link></item>
</channel></rss>
EOF
out=$(SECNEWS_PASSWORD_FILE="$WORK/pw" run --run-once); rc=$?
check "env password file + hit" '[ $rc -eq 0 ] && [[ "$out" == *"命中 1 則"* ]] && [[ "$out" == *"Tomcat 9 緊急更新"* ]]'
mkdir -p "$WORK/cred"; cp "$WORK/pw" "$WORK/cred/secnews-password"
out=$(CREDENTIALS_DIRECTORY="$WORK/cred" run --run-once); rc=$?
check "systemd credential, no dup" '[ $rc -eq 0 ] && [[ "$out" == *"命中 0 則"* ]]'
out=$(printf '%s\n' "$PW" | run --run-once); rc=$?; check "password from stdin" '[ $rc -eq 0 ]'

# 6. --headless 常駐、鎖、停止訊號
SECNEWS_PASSWORD_FILE="$WORK/pw" "$RUN" --headless > "$WORK/daemon.log" 2>&1 &
DPID=$!
for i in $(seq 1 30); do grep -q "下次執行時間" "$WORK/daemon.log" && break; sleep 0.5; done
check "daemon started" 'grep -q "以無介面模式啟動" "$WORK/daemon.log" && grep -q "下次執行時間" "$WORK/daemon.log"'
check "daemon alive" 'kill -0 $DPID 2>/dev/null'
out=$(SECNEWS_PASSWORD=$PW run --run-once); rc=$?; check "lock rc3 while daemon runs" '[ $rc -eq 3 ]'
out=$(run --status); check "status shows running" '[[ "$out" == *"執行中"* ]]'
kill -TERM $DPID; wait $DPID; drc=$?
check "daemon stops on SIGTERM (rc $drc)" '[ $drc -eq 0 ] || [ $drc -eq 143 ]'
check "daemon logged stop" 'grep -q "收到停止訊號" "$WORK/daemon.log"'
check "daily log file" 'ls "$SECNEWS_HOME"/logs/monitor-*.log >/dev/null 2>&1'

# 7. 重設帳號
out=$(printf 'ops2\nNewPass99\nNewPass99\n\n' | run --setup --reset); rc=$?
check "setup --reset" '[ $rc -eq 0 ]'
out=$(SECNEWS_PASSWORD=$PW run --run-once); rc=$?; check "old pw rejected after reset" '[ $rc -eq 1 ]'
out=$(run --status); check "webhook cleared, keywords kept" '[[ "$out" == *"未設定"* ]] && [[ "$out" == *"Tomcat"* ]]'
out=$(SECNEWS_PASSWORD=NewPass99 run --test-chat); rc=$?; check "test-chat without webhook rc1" '[ $rc -eq 1 ]'
out=$(printf 'https://evil.example.com/x\n' | SECNEWS_PASSWORD=NewPass99 run --set-webhook); rc=$?
check "set-webhook rejects non-google" '[ $rc -eq 1 ]'
out=$(printf '%s\n' "$HOOK" | SECNEWS_PASSWORD=NewPass99 run --set-webhook); rc=$?
check "set-webhook ok" '[ $rc -eq 0 ]'

# 8. 程式資料夾內的 JDK（PATH 上沒有 java 也能執行）
APP="$WORK/app"; mkdir -p "$APP"
cp "$ROOT/SecNewsMonitor.jar" "$ROOT/run-linux.sh" "$APP/"
REAL_JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
ln -s "$REAL_JAVA_HOME" "$APP/jdk-21.0.0"
out=$(PATH=/usr/bin:/bin JAVA_HOME= "$APP/run-linux.sh" --version 2>&1 | grep -v '^Picked up');
check "bundled jdk used" '[[ "$out" == *"SecNewsMonitor"* ]]'
out=$(PATH=/nonexistent JAVA_HOME= /bin/bash "$ROOT/run-linux.sh" --version 2>&1); rc=$?
check "no java → clear message" '[ $rc -eq 1 ] && [[ "$out" == *"找不到 Java 17"* ]]'

kill $HTTP_PID 2>/dev/null
rm -rf "$WORK"
echo "linux-cli-test PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ]

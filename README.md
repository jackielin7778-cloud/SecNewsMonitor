# SecNewsMonitor 資安新聞監控

定時抓取台灣資安新聞網站（RSS），比對關鍵字（例如 Java、Tomcat），
有新文章就推播到 Google Chat。純 Java（JDK 17 以上），**不需任何第三方套件**，
同一個 `SecNewsMonitor.jar` 可在 Windows、macOS、Linux 執行（Linux 伺服器可用無介面模式＋systemd）。

## 1. 執行環境

- JDK / JRE **17 以上**（OpenJDK、Eclipse Temurin、Azul Zulu 皆可），**可與 Java 8 並存**
- 檢查：終端機（macOS）或命令提示字元（Windows）輸入 `java -version`
- macOS 列出所有已安裝的 JDK：`/usr/libexec/java_home -V`

`run-mac.command` 尋找 Java 的順序：環境變數 `SECNEWS_JAVA` → `/usr/libexec/java_home -v 17+`
→ SDKMAN（`~/.sdkman/candidates/java`）→ Homebrew openjdk → PATH 上的 `java`。
所以系統預設維持 Java 8 也能執行，不需改動公司其他專案的設定。

`run-windows.bat` 的順序：**本資料夾內的 JDK**（`jdk\bin\javaw.exe`、`jdk-21.x\bin\javaw.exe` 或 `jdk\<任一資料夾>\bin\javaw.exe`）
→ `SECNEWS_JAVA` → `JAVA_HOME` → PATH 上的 `javaw`。

### 把 JDK 放在程式資料夾內（建議用於伺服器）

不安裝、不改系統的 Java／`JAVA_HOME`／`PATH`，其他系統（例如 Tomcat）完全不受影響：

1. 下載 JDK 21 的 **.zip** 版（Windows x64），例如 Temurin：https://adoptium.net/temurin/releases/?os=windows
2. 解壓縮到 SecNewsMonitor 資料夾內，最後的結構如下（資料夾名稱 `jdk` 或 `jdk-21.0.x+y` 都可以）：
   ```
   SecNewsMonitor\
     SecNewsMonitor.jar
     run-windows.bat
     jdk-21.0.x+y\
       bin\javaw.exe
   ```
3. 雙擊 `run-windows.bat`，腳本會自動找到這個 JDK。

`run-mac.command` 也會優先找本資料夾內的 JDK（支援 macOS 的 `Contents/Home` 結構）。

用低於 17 的 Java 開啟時，程式會跳出「Java 版本不足」視窗，並顯示目前使用的 Java 位置。

## 2. 啟動

| 系統 | 方式 |
|---|---|
| macOS | 第一次先在終端機執行 `chmod +x run-mac.command`，之後在 Finder 雙擊 `run-mac.command` |
| Windows | 雙擊 `run-windows.bat` |
| Linux | `./run-linux.sh`（桌面版開視窗）；伺服器見下方「Linux 伺服器」 |
| 通用 | `java -Xmx96m -XX:+UseSerialGC -jar SecNewsMonitor.jar` |

### macOS：出現「Apple 無法驗證 run-mac.command 是否為惡意軟體」

從網路下載的檔案會被 macOS 加上隔離標記（quarantine），本程式沒有 Apple 開發者簽章，
所以 Gatekeeper 會擋下 Finder 雙擊。請按「**完成**」（不要按「丟到垃圾桶」），再擇一處理：

1. **用終端機執行**（最簡單）：
   ```bash
   cd 解壓縮後的資料夾/SecNewsMonitor
   bash run-mac.command
   ```
2. **移除隔離標記**（之後就能在 Finder 雙擊）：
   ```bash
   xattr -dr com.apple.quarantine 解壓縮後的資料夾/SecNewsMonitor
   ```
3. **系統設定 → 隱私權與安全性**，往下找到被阻擋的 `run-mac.command`，按「強制打開」。

較新版 macOS 的警告視窗只有「完成」與「丟到垃圾桶」，舊版說明中的「按右鍵 → 打開」可能已不適用。

### Windows：出現「Windows 已保護您的電腦」（SmartScreen）

從網路下載的 `.bat` 可能被 SmartScreen 攔下：按「**其他資訊**」→「**仍要執行**」。

### 帳號

第一次啟動會要求**建立管理員帳號**（密碼至少 8 碼，需含英文與數字）。
密碼欄右側的**眼睛圖示**可切換顯示明碼／隱藏（登入、建立帳號、變更密碼畫面皆有），
輸入前建議把輸入法切到英文（ABC），避免輸入法造成密碼與預期不同。

**忘記密碼**：在登入畫面按左下角「忘記密碼？重設帳號」，輸入 `RESET` 確認。
- 會刪除：帳號密碼、已儲存的 Google Chat Webhook（以舊密碼加密，無法解開）
- 會保留：網站列表、關鍵字、排程、已讀紀錄、執行紀錄
- 重設後會出現「建立帳號」畫面；登入後到「Google Chat 設定」重新貼上 Webhook
  （Google Chat 聊天室名稱 ▼ → 應用程式與整合 → 該 Webhook 的 ⋮ → 複製連結）

## 3. 功能

| 分頁 | 說明 |
|---|---|
| 資安網站列表 | 查詢、新增、修改、刪除、啟用/停用、**全文比對**開關、測試抓取。類型 RSS（建議）或 HTML（沒有 RSS 的網站） |
| 監控關鍵字 | 可用逗號一次輸入多個。不分大小寫；英數關鍵字以完整單字比對（Java 不會誤中 JavaScript） |
| 監控排程 | 每隔 N 分鐘（最少 5 分鐘，**可限制執行時段與星期**）／每日指定時間／每週指定星期幾與時間；可立即執行、清除已讀紀錄；有未儲存的變更會顯示提示 |
| Google Chat 設定 | 貼上完整 Webhook URL，或分別輸入 Space ID、Key、Token；**單次最多推播則數**（預設 20）；可傳送測試訊息 |
| 執行紀錄 | 每次抓取結果、錯誤、推播紀錄；**紀錄檔一天一個，可設定保留天數**（預設 30 天，0 = 永久保留） |

### 全文比對（v1.1）

有些網站的 RSS 只有標題（例如資安院的漏洞警訊公告），關鍵字只寫在內文時比對不到。
在「資安網站列表」勾選該網站的「全文比對」後：

- 標題／摘要沒命中的**新文章**，會再下載文章網頁、取出內文比對（去除程式碼、樣式、選單、頁首頁尾）
- 每次執行、每個網站最多抓 **30 篇**，每篇間隔 1 秒；超過的留待下次執行，不會漏掉
- 推播訊息會附上命中的內文片段（「內文：…Java 序列化物件…」）

### 執行時段（v1.3）

「每隔 N 分鐘」模式可勾選「只在 HH:MM～HH:MM 之間執行」與「執行日」：
- 例如平日 08:00～18:00：晚上與週末不執行、不推播
- 結束時間早於開始時間表示跨夜，例如 22:00～06:00（跨夜時段以開始那天的星期為準）
- 時段外發布的新聞不會漏掉，會在下一個時段開始時（例如隔天 08:00）一起比對推播

### 大型 RSS（v1.3）

RSS／Atom 改為串流解析（邊下載邊處理），記憶體用量與檔案大小無關；單一 feed 上限 50 MB。
HTML 列表頁與全文比對的文章頁仍為 5 MB。

### 文章日期過濾（v1.2）

「監控排程」→「只推播發布日期在 N 天內的文章」（預設 30 天，0 = 不限）。
程式會讀取 RSS 的 `pubDate`／`dc:date`、Atom 的 `published`／`updated`，超過天數的文章不比對、不抓內文、不推播
（例如 Oracle 舊 RSS 裡 2011～2013 年的公告）。RSS 沒有提供日期、或 HTML 模式抓到的文章，照常比對。
推播訊息會附上「發布：YYYY-MM-DD」。

### 已讀紀錄（v1.2）

`seen.txt` 改為**每個網站分開保存**（每行：`網站名稱<TAB>文章網址`）。每次成功抓取後，
只保留目前 feed 中的文章＋最近 2,000 筆，不再有全域 5,000 筆上限；
v1.1 以前因單一網站項目太多（例如 Microsoft Security Update Guide 約 5,000 則）擠掉其他網站紀錄、造成重複推播的問題已修正。
舊格式會在所有啟用的網站都成功抓取一次後，自動轉換為新格式。

### 抓取重試（v1.4.1）

網站暫時故障（HTTP 502／503／504、429、逾時、連線中斷）時，自動在 3、6 秒後重試，最多 2 次；
仍失敗才記為 ✗，並寫明「已重試 2 次」。404 等明確錯誤不重試。

版本號顯示在視窗標題，詳細資訊在「說明 → 關於」；Linux 命令列用 `--version`。

### 推播上限與重試（v1.1）

- 一次命中很多則時，只推播前 N 則（預設 20），並附「另有 X 則」提示；其餘列在執行紀錄
- Google Chat 回應 HTTP 429（頻率限制）或 5xx 時，依 Retry-After 或 2／4／8／16 秒自動重試，最多 4 次
- 失敗時，執行紀錄會寫明第幾則失敗、HTTP 狀態碼與原因

關閉視窗時，程式會縮到 **選單列（macOS）／系統匣（Windows）** 繼續執行排程；
要完全結束請用選單「帳號 → 結束程式」或圖示右鍵選單。

## 4. 預設的台灣資安新聞來源

| 名稱 | RSS 網址 | 預設 |
|---|---|---|
| iThome 資安頻道 | https://www.ithome.com.tw/rss/security | 啟用 |
| iThome 新聞（全部） | https://www.ithome.com.tw/rss | 停用 |
| TWCERT/CC 資安新聞 | https://www.twcert.org.tw/tw/rss-104-1.xml | 啟用 |
| TWCERT/CC TVN 漏洞公告 | https://www.twcert.org.tw/tw/rss-132-1.xml | 啟用 |
| 資安院 漏洞警訊公告 | https://www.nics.nat.gov.tw/RSS2.xml | 啟用 |
| 資安趨勢部落格（趨勢科技） | https://blog.trendmicro.com.tw/?feed=rss2 | 啟用 |
| TechNews 科技新報（綜合科技） | https://technews.tw/tn-rss/ | 停用 |

網址查證日期：2026-10-27（TWCERT/CC 官方 RSS 說明頁、Feeder 的 feed 目錄）。
其中 iThome 資安頻道的網址只在 Feeder 目錄中列出，未看到其狀態檢查結果，請先用「測試抓取」確認。
「iThome 新聞」與「TechNews」不限資安主題，預設停用以減少雜訊。

**未預設加入**（可自行評估後用「新增」加入）：
- 資安人（informationsecurity.com.tw）：未找到 RSS，且該站 robots.txt 不允許自動存取
- 技服中心（nccst.nat.gov.tw）：有 RSS 服務頁，但未取得確切網址，且 robots.txt 不允許自動存取
- 數位發展部資安署新聞稿：未找到 RSS

使用 HTML 模式抓取網站前，請先確認該網站的使用條款與 robots.txt 允許。

## 5. Google Chat Webhook

1. Google Chat 開啟要接收通知的聊天室（Space）
2. 聊天室名稱 → 應用程式與整合（Apps & integrations）→ Webhook → 新增
3. 複製網址（格式 `https://chat.googleapis.com/v1/spaces/SPACE_ID/messages?key=KEY&token=TOKEN`），貼到「Google Chat 設定」
4. 按「傳送測試訊息」確認

Google Workspace 管理員必須允許使用 Webhook。選單名稱以 Google 實際畫面為準。

## 6. 資料與安全

資料夾：
- macOS：`~/Library/Application Support/SecNewsMonitor`
- Windows：`%APPDATA%\SecNewsMonitor`

| 檔案 | 內容 |
|---|---|
| config.properties | 帳號（PBKDF2 雜湊，非明文）、排程、**加密後**的 Webhook |
| sites.tsv | 網站列表 |
| keywords.txt | 關鍵字 |
| seen.txt | 已處理文章（每個網站分開保存，用來去重） |
| logs/monitor-YYYY-MM-DD.log | 執行紀錄，一天一個檔案；超過保留天數自動刪除（每天第一筆紀錄寫入時、程式啟動時、按「儲存」或「立即清理」時） |

保留天數的計算：設定 30 天、今天 10/27 → 保留 9/28～10/27，刪除 9/27 以前。
自動刪除只會刪除檔名符合 `monitor-YYYY-MM-DD.log` 的檔案。
v1.0 的 `monitor.log` 會在升級後第一次啟動時，自動搬到 `logs/` 並依日期命名。

- 密碼：PBKDF2WithHmacSHA256，210,000 次迭代
- Webhook：AES-256-GCM 加密，金鑰由登入密碼推導，**忘記密碼就無法解密 Webhook**——
  請用登入畫面的「忘記密碼？重設帳號」，其他設定都會保留，只需重新貼上 Webhook
- RSS 解析已關閉 DOCTYPE／外部實體，防 XXE 攻擊
- 同時只能開一個程式（避免重複推播）
- 連續 5 次登入失敗鎖定 60 秒

## 7. 系統資源

- 只有一條低優先權背景執行緒；每 30 秒比對一次時鐘（電腦睡眠喚醒後也會補跑）
- 啟動參數：`-Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`
- 網站逐一抓取、不併發；單頁最多讀 5 MB；逾時 30 秒

## 8. 開機自動啟動（選用）

- macOS：系統設定 → 一般 → 登入項目 → 加入 `run-mac.command`
- Windows：`Win + R` 輸入 `shell:startup`，把 `run-windows.bat` 的捷徑放進去

## 9. 重新編譯

- macOS：`./build.sh`
- Windows：`build.bat`

## Linux 伺服器（無圖形介面）

Linux 伺服器通常沒有 DISPLAY，請用**命令列＋無介面模式**。所有指令：`./run-linux.sh --help`

| 指令 | 用途 |
|---|---|
| `--setup` | 第一次使用：建立帳號、設定 Google Chat Webhook |
| `--headless` | 常駐背景，依排程執行（給 systemd 用） |
| `--run-once` | 立即執行一次後結束 |
| `--set-webhook`／`--test-chat` | 變更 Webhook／傳送測試訊息 |
| `--status` | 顯示設定與執行狀態（不需密碼） |
| `--setup --reset` | 忘記密碼時重設帳號（清除 Webhook，其他設定保留） |

**密碼來源**（依序）：`--password-file 檔案` → `SECNEWS_PASSWORD_FILE` → systemd credential
（`$CREDENTIALS_DIRECTORY/secnews-password`）→ `SECNEWS_PASSWORD` → 終端機輸入。
無介面執行時建議用密碼檔或 systemd credential，檔案權限 600。

**資料夾**：`SECNEWS_HOME`，預設 `~/.secnewsmonitor`，權限自動設為 700。
網站 `sites.tsv`、關鍵字 `keywords.txt` 可直接編輯；排程等設定在 `config.properties` 的 `schedule.*`，
也可以在有圖形介面的電腦設定好，再把 `config.properties`、`sites.tsv`、`keywords.txt` 複製過來（登入密碼相同）。
修改前請先停止程式。

### 安裝成 systemd 服務（範例：Ubuntu／RHEL 系列）

```bash
# 1. 程式與 JDK（Temurin 21 Linux x64 .tar.gz 解壓縮到程式資料夾內）
sudo mkdir -p /opt/SecNewsMonitor
sudo cp SecNewsMonitor.jar run-linux.sh /opt/SecNewsMonitor/
sudo tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_*.tar.gz -C /opt/SecNewsMonitor/
sudo chmod +x /opt/SecNewsMonitor/run-linux.sh

# 2. 服務帳號與資料夾
sudo useradd --system --home /var/lib/secnewsmonitor --shell /usr/sbin/nologin secnews
sudo install -d -o secnews -g secnews -m 700 /var/lib/secnewsmonitor

# 3. 建立帳號、設定 Webhook（以服務帳號身分）
sudo -u secnews SECNEWS_HOME=/var/lib/secnewsmonitor /opt/SecNewsMonitor/run-linux.sh --setup

# 4. 密碼檔（只有 root 可讀；systemd 以 credential 方式交給服務）
sudo install -d -m 700 /etc/secnewsmonitor
sudo sh -c 'umask 077; read -r -s -p "密碼：" p; echo; printf "%s" "$p" > /etc/secnewsmonitor/password'

# 5. 安裝並啟動服務
sudo cp deploy/secnewsmonitor.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now secnewsmonitor
journalctl -u secnewsmonitor -f        # 查看執行紀錄
```

- 修改網站或關鍵字：`sudo systemctl stop secnewsmonitor` → 編輯 `/var/lib/secnewsmonitor/*.tsv|txt` → `start`
- systemd 249 以下沒有 `LoadCredential` 時，改用服務檔中註解的 `SECNEWS_PASSWORD_FILE`，並 `chown secnews /etc/secnewsmonitor/password`
- 桌面版 Linux 執行圖形介面需要中文字型（例如 Noto Sans CJK：`fonts-noto-cjk`／`google-noto-sans-cjk-fonts`）

## 10. 版控與發佈

**提交到版控**：`src/`、`test/`、`docs/`、建置／執行／測試腳本、`README.md`、`CHANGELOG.md`、`.gitignore`、`.gitattributes`。

**不提交**（`.gitignore` 已排除）：
- `build/`、`SecNewsMonitor.jar`、`*.zip`：由 `build.sh`／`build.bat` 產生，改放在發佈區（例如 Git 的 Release／Tag 附件或公司檔案伺服器）
- `jdk*/`：第三方 JDK（約 200 MB），部署時另外下載放入
- `config.properties`、`seen.txt`、`logs/` 等執行期資料：**含密碼雜湊與加密的 Google Chat Webhook，絕對不可提交**

**測試**：`./run-tests.sh`（macOS／Linux）或 `run-tests.bat`（Windows），不需網路與圖形介面。修改程式後、提交前請先執行。
Linux 另有命令列整合測試：先 `./build.sh`，再 `./test/linux-cli-test.sh`（需要 python3）。

**發佈新版本**：
1. 更新 `build.sh`／`build.bat` 中的 `Implementation-Version`，並在 `CHANGELOG.md` 記錄變更
2. 執行測試 → 建置 jar
3. 提交並打上版本標籤（例如 `git tag v1.2.0`），jar 放到發佈區

**換行字元**：`.gitattributes` 已設定 `.bat` 為 CRLF、`.sh`／`.command` 為 LF。
在 Windows 上首次提交 `.sh`／`.command` 時，請執行
`git update-index --chmod=+x build.sh run-tests.sh run-mac.command run-linux.sh test/linux-cli-test.sh`，保留執行權限。

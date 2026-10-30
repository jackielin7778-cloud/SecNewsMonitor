# SecNewsMonitor 資安新聞監控

定時抓取台灣資安新聞網站（RSS），比對關鍵字（例如 Java、Tomcat），
有新文章就推播到 Google Chat。純 Java（JDK 17 以上），**不需任何第三方套件**，
同一個 `SecNewsMonitor.jar` 可在 Windows、macOS、Linux 執行（Linux 伺服器可用無介面模式＋systemd）。

v1.5 起支援**多頻道**：例如「資安新聞」推到資安聊天室、「AI 新聞」推到 AI 聊天室，
每個頻道有自己的網站列表、關鍵字、排程與 Google Chat Webhook。

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

主視窗上方是**頻道列**：選擇頻道、新增頻道、重新命名、刪除頻道、啟用／停用此頻道，並顯示該頻道的下次執行時間。
下方前 4 個分頁都是**目前所選頻道**的設定；「執行紀錄」是全部頻道共用（每行前面有 `[頻道名稱]`）。

| 分頁 | 說明 |
|---|---|
| 網站列表 | 查詢、新增、修改、刪除、啟用/停用、**全文比對**開關、測試抓取、**匯入／匯出 TSV 檔**。類型 RSS（建議）或 HTML（沒有 RSS 的網站） |
| 監控關鍵字 | 可用逗號一次輸入多個。**複製全部**（一行一個到剪貼簿）、**貼上匯入**（貼上多行清單，自動略過重複）。不分大小寫；英數關鍵字以完整單字比對（Java 不會誤中 JavaScript） |
| 監控排程 | 每隔 N 分鐘（最少 5 分鐘，**可限制執行時段與星期**）／每日指定時間／每週指定星期幾與時間；可立即執行、清除已讀紀錄；有未儲存的變更會顯示提示 |
| 推播設定 | 三個子分頁：**Google Chat**（Webhook、單次最多推播則數）、**LINE**（v1.8）、**Gmail**（v1.8）；可同時啟用多個，各自可傳送測試訊息 |
| 執行紀錄 | 每次抓取結果、錯誤、推播紀錄；**紀錄檔一天一個，可設定保留天數**（預設 30 天，0 = 永久保留） |

### 多頻道（v1.5）
- **新增頻道**：頻道列按「新增頻道」→ 輸入名稱（例如「AI 新聞」）→ 依序設定網站列表、關鍵字、排程、Google Chat。
  新頻道的網站與關鍵字是空的；AI 新聞可在「網站列表」按「匯入…」載入 `docs/ai-sites.tsv`。
- **各自獨立**：每個頻道有自己的網站列表、關鍵字、排程（含執行時段）、Webhook、單次推播上限、已讀紀錄。
  同一個網站可以出現在多個頻道。
- **重複命中**：同一則新聞符合多個頻道的關鍵字時，每個頻道都會推播到自己的聊天室。
- **執行順序**：各頻道依自己的排程執行；同一時間只執行一個頻道，其他到期的頻道會在它結束後接著執行（降低資源與連線數）。
- **停用頻道**：取消勾選「啟用此頻道」→ 不會自動執行，設定都保留。
- **刪除頻道**：需輸入頻道名稱確認；會刪除該頻道的所有設定與已讀紀錄（建議先「匯出…」網站列表）。至少保留一個頻道。
- **推播標題**：Google Chat 訊息開頭為「🔔 *頻道名稱* 日期時間｜共 N 則」。
- **由舊版升級**：第一次啟動時，原本的網站列表、關鍵字、排程、Google Chat 設定、已讀紀錄自動轉移到「資安新聞」頻道，
  不需重新設定；原檔備份在資料夾內的 `backup-before-1.5/`。

### 商品監控（v1.6，PChome 24h；v1.7 起加入 momo、露天）
- **新增**：頻道列「新增頻道」→ 選「商品監控」。頻道名稱前會顯示［商品］。
- **搜尋網站**（v1.7）：商品清單上方勾選 PChome 24h、momo、露天（可複選）；每個商品會在勾選的網站各搜尋一次。
- **商品清單**：每一項設定
  - 搜尋關鍵字：送到 PChome 搜尋，例如 `iPhone 17 Pro 256GB`
  - 名稱需包含：商品名稱必須包含的字（空白分隔、全部都要有、不分大小寫）
  - 排除字（v1.6.1）：商品名稱出現任一個就排除（空白分隔），例如 `保護貼 保護殼 膜 支架`
  - 最低價格（v1.6.1）：低於此價格的商品不列入，可排除便宜的配件；0 = 不設定
  - 價格上限：0 = 不設定
- **通知條件**（可勾選）：新商品、低於設定價格（從高於變成低於時通知一次）、降價（比上次便宜）、補貨（v1.7，只適用 momo、露天）。同一件商品符合多個條件時合併成一則。
- **第一次搜尋**：只記錄現有商品、不通知「新商品」，但已低於設定價格的商品會通知。
- **測試搜尋**：顯示搜尋結果，✓ 表示符合條件，✗ 會註明排除原因（缺少某字、含排除字、低於最低價格）。
- **搜尋範圍**（v1.6.2）：依相關度排序，最多抓 5 頁（100 件），每頁間隔 1 秒。執行紀錄會列出各排除原因的件數，方便調整條件。
- **限制**：
  - 使用 PChome 網站內部的搜尋介面，不是官方公開 API，網站改版時可能失效。可在 `channels/頻道ID/channel.properties` 修改 `product.searchUrl`（`{q}` 代入搜尋關鍵字）。
  - 每次抓取前檢查 robots.txt；網站不允許時略過並記錄在執行紀錄。
  - PChome 搜尋結果沒有庫存資訊，補貨通知只適用 momo、露天。蝦皮（需登入與反爬蟲驗證）不支援。
  - momo 讀取搜尋頁內嵌的商品資料（JSON-LD）；露天使用搜尋與商品資料 JSON 介面。網址可在 channel.properties 以 `product.momo.searchUrl`、`product.ruten.searchUrl`、`product.ruten.prodUrl` 修改。
  - 建議排程每 30～60 分鐘一次，避免對網站造成負擔；購物網站的使用條款可能限制自動化擷取，對外散布前請先確認。

### 全文比對（v1.1）

有些網站的 RSS 只有標題（例如資安院的漏洞警訊公告），關鍵字只寫在內文時比對不到。
在「網站列表」勾選該網站的「全文比對」後：

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

### LINE（v1.8）
LINE Notify 已於 2025 年 3 月 31 日停止服務，本程式改用 **LINE 官方帳號的 Messaging API**。
1. 建立 LINE 官方帳號，在官方帳號設定中啟用 Messaging API
2. 到 LINE Developers（developers.line.biz）開啟該 channel →「Messaging API」頁籤 → 發行 Channel access token（long-lived）
3. 推播對象二選一：
   - **指定的人**：填使用者 ID（U 開頭加 32 個英數字）；自己的 ID 在「Basic settings」頁籤最下方「Your user ID」
   - **所有好友**：推給加了官方帳號好友的所有人
4. 收件人需先把官方帳號加為好友
5. 「推播設定」→「LINE」貼上 Token、填對象，按「傳送測試訊息」

**則數限制**：台灣免費方案（輕用量）每月 200 則，**每次推播的每位收件人（或每位好友）算 1 則**，超過後當月無法傳送（程式會記錄「超過本月可傳送則數」）。
例如每小時執行一次、每次都有命中、推給 1 個人，一個月最多約 720 則，會超過免費額度；建議把排程設長一點，或只在重要頻道啟用 LINE。
群組 ID 需要架設 Webhook 伺服器才能取得，本程式不提供。

### Gmail（v1.8）
透過 Gmail 的 SMTP 伺服器（smtp.gmail.com，連接埠 465，全程 TLS，驗證伺服器憑證與主機名稱）寄出純文字郵件。
1. Google 帳戶 →「安全性」→ 開啟「兩步驟驗證」
2. 在 Google 帳戶搜尋「應用程式密碼」→ 建立 → 複製 16 個字母的密碼（**不是** Google 登入密碼）
3. 「推播設定」→「Gmail」填寄件 Gmail、應用程式密碼、收件人（可多位），按「寄送測試信」

Google Workspace 帳號需管理員允許應用程式密碼；公司防火牆若封鎖連接埠 465 會寄不出去。

### 多個推播管道同時啟用
- 每個頻道可同時啟用 Google Chat、LINE、Gmail，命中時全部推播，內容相同（LINE、Gmail 為純文字）。
- **至少一個管道成功就視為已送出**；失敗的管道記錄在執行紀錄，不會讓成功的管道下次重複收到。全部失敗時，下次執行再推播。
- Webhook、LINE Token、Gmail 應用程式密碼都以登入密碼推導的金鑰加密儲存；變更密碼時一併重新加密，重設帳號時一併清除。

## 6. 資料與安全

資料夾：
- macOS：`~/Library/Application Support/SecNewsMonitor`
- Windows：`%APPDATA%\SecNewsMonitor`

| 檔案 | 內容 |
|---|---|
| config.properties | 帳號（PBKDF2 雜湊，非明文）、紀錄保留天數、頻道順序 |
| channels/頻道ID/channel.properties | 頻道名稱、啟用、**加密後**的 Webhook、推播設定、排程（`schedule.*`） |
| channels/頻道ID/sites.tsv | 該頻道的網站列表 |
| channels/頻道ID/keywords.txt | 該頻道的關鍵字 |
| channels/頻道ID/seen.txt | 該頻道已處理的文章（每個網站分開保存，用來去重） |
| backup-before-1.5/ | 由 v1.4 以前升級時的原始檔備份 |

頻道 ID 是 `c1`、`c2`…（`c1` 通常是「資安新聞」），名稱可在畫面上改，ID 不變。`--status` 會列出每個頻道的 ID。
| logs/monitor-YYYY-MM-DD.log | 執行紀錄，一天一個檔案；超過保留天數自動刪除（每天第一筆紀錄寫入時、程式啟動時、按「儲存」或「立即清理」時） |

保留天數的計算：設定 30 天、今天 10/27 → 保留 9/28～10/27，刪除 9/27 以前。
自動刪除只會刪除檔名符合 `monitor-YYYY-MM-DD.log` 的檔案。
v1.0 的 `monitor.log` 會在升級後第一次啟動時，自動搬到 `logs/` 並依日期命名。

- 密碼：PBKDF2WithHmacSHA256，600,000 次迭代（v1.7.1 起；舊帳號登入時自動升級）
- Webhook、LINE Token、Gmail 應用程式密碼：AES-256-GCM 加密，金鑰由登入密碼推導，**忘記密碼就無法解密 Webhook**——
  請用登入畫面的「忘記密碼？重設帳號」，其他設定都會保留，只需重新貼上 Webhook。
  建立新帳號成功後才會清除舊帳號；在建立帳號畫面按取消，原帳號不變（v1.7.1）
- 變更密碼分階段寫入，中途中斷（當機、斷電）也不會讓 Webhook 無法解密（v1.7.1）
- RSS 解析已關閉 DOCTYPE／外部實體，防 XXE 攻擊
- 同時只能開一個程式（避免重複推播）
- 連續 5 次登入失敗鎖定 60 秒；命令列登入失敗等待 3 秒
- 密碼檔（`--password-file`）若群組或其他使用者可讀，拒絕使用（v1.7.1 起，原本只警告）

### 對外連線的安全防護（v1.7.1）
- **不連線到內網／本機位址**：網站網址、RSS 裡的文章連結、網站的轉址，只要指向本機（127.0.0.1、localhost）、
  內網（10.x、172.16–31.x、192.168.x、fc00::/7）、169.254.x（含雲端主機中繼資料服務）等位址，一律拒絕並記錄原因；
  每一次轉址都重新檢查，最多 5 次。只允許 http／https。
  確實要監控公司內網網站時，啟動前設定環境變數 `SECNEWS_ALLOW_PRIVATE=1`（或 Java 參數 `-Dsecnews.allowPrivate=true`）。
  已知限制：檢查時與連線時的 DNS 結果可能不同（DNS rebinding），要完全杜絕需在防火牆限制。
- **推播內容防注入**：標題、內文片段、商品名稱等外部文字推播前會把 Google Chat 的格式符號（`<` `>` `*` `_` `~` 反引號 `|`）
  換成全形、去除換行與隱藏的控制字元，因此無法觸發 `<users/all>` 全員通知或偽裝連結；連結只接受 http／https。
- **網頁解析不會被拖慢**：HTML 解析改為線性掃描，惡意網頁無法讓監控卡住；回應內容讀取總時限 90 秒。
- **推播失敗不遺失**：Google Chat 推播失敗或超過單次上限時，那些文章／商品通知會在下次執行再推播。
- 程式畫面中的表格、清單一律以純文字顯示，不解讀 HTML。

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
| `--status` | 顯示設定、執行狀態與所有頻道（不需密碼） |
| `--add-channel 名稱 [--type product]` | 新增頻道（之後編輯該頻道的 `sites.tsv`、`keywords.txt`；商品頻道編輯 `products.tsv`，每行：名稱、搜尋關鍵字、名稱需包含、價格上限、true、排除字、最低價格，以 Tab 分隔） |
| `--delete-channel 名稱 --yes` | 刪除頻道 |
| `--channel 名稱或ID` | 搭配 `--run-once`、`--set-webhook`、`--test-chat` 指定頻道；`--run-once` 未指定時執行全部啟用中的頻道，其他指令未指定時用第一個頻道 |
| `--setup --reset` | 忘記密碼時重設帳號（清除 Webhook，其他設定保留） |

**密碼來源**（依序）：`--password-file 檔案` → `SECNEWS_PASSWORD_FILE` → systemd credential
（`$CREDENTIALS_DIRECTORY/secnews-password`）→ `SECNEWS_PASSWORD` → 終端機輸入。
無介面執行時建議用密碼檔或 systemd credential，檔案權限 600。

**資料夾**：`SECNEWS_HOME`，預設 `~/.secnewsmonitor`，權限自動設為 700。
各頻道的網站 `channels/頻道ID/sites.tsv`、關鍵字 `channels/頻道ID/keywords.txt` 可直接編輯；
排程等設定在 `channels/頻道ID/channel.properties` 的 `schedule.*`。
也可以在有圖形介面的電腦設定好，再把 `config.properties` 與整個 `channels/` 資料夾複製過來（登入密碼相同）。
修改前請先停止程式。

**多頻道範例**（AI 新聞推到另一個聊天室）：
```bash
./run-linux.sh --add-channel "AI 新聞"            # 輸出頻道 ID，例如 c2
cp docs/ai-sites.tsv ~/.secnewsmonitor/channels/c2/sites.tsv
printf 'AI\n生成式\nLLM\n' > ~/.secnewsmonitor/channels/c2/keywords.txt
./run-linux.sh --set-webhook --channel c2          # 貼上 AI 聊天室的 Webhook
./run-linux.sh --test-chat --channel c2
./run-linux.sh --run-once --channel c2             # 第一次只建立基準
```
系統語系不是 UTF-8 時，`run-linux.sh` 會自動改用 `C.UTF-8` 執行本程式，避免中文頻道名稱變亂碼；也可以直接用頻道 ID（`c2`）。

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

修改程式前請先看 [SECURITY-CHECKLIST.md](SECURITY-CHECKLIST.md)（資安檢查表），交付前逐項確認。

資安靜態掃描：`./security-scan.sh`（Windows：`security-scan.bat`）。使用 SpotBugs 4.9.2＋Find Security Bugs 1.14.0 掃描 `SecNewsMonitor.jar`，
第一次執行會下載工具到 `tools/`（需要網路，下載後驗證雜湊值），報告在 `build/security-scan.html`。

**提交到版控**：`src/`、`test/`、`docs/`、建置／執行／測試腳本、`README.md`、`CHANGELOG.md`、`.gitignore`、`.gitattributes`。

**不提交**（`.gitignore` 已排除）：
- `build/`、`SecNewsMonitor.jar`、`*.zip`：由 `build.sh`／`build.bat` 產生，改放在發佈區（例如 Git 的 Release／Tag 附件或公司檔案伺服器）
- `jdk*/`：第三方 JDK（約 200 MB），部署時另外下載放入
- `config.properties`、`channels/`、`seen.txt`、`logs/` 等執行期資料：**含密碼雜湊與加密的 Google Chat Webhook，絕對不可提交**

**測試**：`./run-tests.sh`（macOS／Linux）或 `run-tests.bat`（Windows），不需網路與圖形介面。修改程式後、提交前請先執行。
Linux 另有命令列整合測試：先 `./build.sh`，再 `./test/linux-cli-test.sh`（需要 python3）。

**發佈新版本**：
1. 更新 `build.sh`／`build.bat` 中的 `Implementation-Version`，並在 `CHANGELOG.md` 記錄變更
2. 執行測試 → 建置 jar
3. 提交並打上版本標籤（例如 `git tag v1.2.0`），jar 放到發佈區

**換行字元**：`.gitattributes` 已設定 `.bat` 為 CRLF、`.sh`／`.command` 為 LF。
在 Windows 上首次提交 `.sh`／`.command` 時，請執行
`git update-index --chmod=+x build.sh run-tests.sh run-mac.command run-linux.sh test/linux-cli-test.sh`，保留執行權限。

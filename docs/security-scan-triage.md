# 靜態掃描判讀紀錄

每次執行 `security-scan.sh`／`security-scan.bat` 後，把判讀結果記在這裡，下次掃描時只需要看新增的項目。
「已修正」表示程式已改；「誤報／接受」要寫出理由。

## 2026-10-30：v1.7.1（SpotBugs 4.9.2 + Find Security Bugs 1.14.0）

共 101 項：高 2、中 25、低 74。下表依類別判讀。

| 代碼 | 數量 | 說明 | 判讀 |
|---|---|---|---|
| SECUNI | 18 | 大小寫轉換／`equalsIgnoreCase` 可能被 Unicode 字元繞過 | **非漏洞，但已加強一處**。程式中的大小寫轉換都用 `Locale.ROOT`，用途是關鍵字比對、網址 scheme、設定值，不是帳號比對（帳號用 `equals` 完全比對）。唯一跟安全有關的是 Webhook 主機檢查 `"chat.googleapis.com".equalsIgnoreCase(host)`：實測 `"googleapıs".equalsIgnoreCase("googleapis")` 為 true，但含非 ASCII 字元的網址，`URI.getHost()` 會回傳 null 而被拒絕，所以無法利用（已驗證）。仍改為 `toLowerCase(Locale.ROOT).equals(...)`，並加上測試。 |
| SECPTI | 2 | 檔案路徑來自使用者輸入 | **誤報（設計如此）**。`--password-file`、`SECNEWS_HOME` 和匯出檔名，都是本機使用者自己指定的路徑，沒有權限提升的問題。 |
| DM_CONVERT_CASE | 1 | `App.main` 的 `toLowerCase()` 沒指定 Locale | **已修正**，改用 `Locale.ROOT`。實際影響很小，因為 os.name 不含大寫 I，土耳其語系也不會轉錯。 |
| UwF | 1 | `ProductMonitor.Product.link` 從未寫入 | **已修正**，刪除這個沒有用到的欄位。商品連結一律由網站樣板與商品 ID 產生。 |
| UPM | 1 | `LoginDialog.msg(String)` 沒有被呼叫 | **已修正**，刪除。這是 v1.7.1 改寫重設帳號流程後留下的。 |
| IS2 | 1 | `Store.config` 有 28% 的存取未加鎖 | **誤報**。未加鎖的存取都在建構子（讀取設定、轉移舊版設定），當時物件還沒交給其他執行緒。 |
| LI_LAZY_INIT_STATIC | 1 | `Cli.stdin` 延遲初始化 | **接受**。只在命令列主執行緒使用。 |
| NP | 2 | 可能的 null 存取（`AppLog.cleanup`、`Store.loadChannels`） | **接受**。檔案系統列出的目錄項目，`getFileName()` 不會是 null。 |
| DE（高） | 6 | 忽略例外 | **接受（設計如此）**。例如：設定介面外觀失敗就用預設值、讀不懂排程設定就用預設值、編碼名稱無效就用 UTF-8、寫入紀錄檔失敗不能再寫紀錄。 |
| EI2 | 4 | 建構子保存外部傳入的物件 | **誤報**。`Store`、`MonitorService` 這類服務物件本來就是共用的。 |
| REC | 20 | `catch (Exception)` 會連 RuntimeException 一起攔下 | **接受**。用在 UI 與背景工作的最外層，目的是不讓單一錯誤讓整個程式停擺；錯誤都有寫進紀錄。 |
| FE | 1 | 浮點數相等比較 | **誤報**。`d == Math.rint(d)` 是用來判斷整數，是刻意的寫法。 |
| Se、PA、THROWS、SIC、FS | 43 | 序列化欄位、public 欄位、throws Exception、內部類別、格式字串 | **接受（程式風格）**。Swing 面板不會被序列化；其餘不影響安全。 |

**沒有掃出的類型（工具的限制）：** 這份掃描沒有回報 SSRF、ReDoS、訊息注入、死結。原因可能是 v1.7.1 已經修正，也可能是靜態規則本來就偵測不到。我們沒有用 v1.7.0 掃描比對，所以無法確定是哪一個。這類問題仍要靠 `SECURITY-CHECKLIST.md` 與獨立審查。

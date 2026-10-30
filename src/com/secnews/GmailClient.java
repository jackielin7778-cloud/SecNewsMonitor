package com.secnews;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gmail 寄信（v1.8）：透過 Gmail 的 SMTP 伺服器（smtp.gmail.com:465，全程 TLS）寄出純文字郵件。
 * 需要 Google 帳戶開啟兩步驟驗證，並建立「應用程式密碼」（16 個字元），不使用 Google 帳戶登入密碼。
 * 不使用第三方函式庫；TLS 會驗證伺服器憑證與主機名稱。
 */
public final class GmailClient {
    /** 測試時可改成本機假伺服器。 */
    static String host = "smtp.gmail.com";
    static int port = 465;
    /** 只給自動測試用：不使用 TLS（本機假伺服器）。 */
    static boolean plainForTest = false;

    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** 不先 trim：含空白、換行等字元一律視為格式不符（v1.8.0，防標頭注入）。 */
    public static void validateAddress(String a) {
        if (a == null || a.length() > 254 || !ADDRESS.matcher(a).matches()) {
            throw new IllegalArgumentException("Email 格式不符：" + ChatText.oneLine(a, 60));
        }
    }

    /** 應用程式密碼：Google 顯示為 4 組 4 個字母，去除空白後為 16 個英文字母。 */
    public static String normalizeAppPassword(String p) {
        String s = p == null ? "" : p.replaceAll("\\s+", "");
        if (!s.matches("[A-Za-z]{16}")) {
            throw new IllegalArgumentException("應用程式密碼應為 16 個英文字母（Google 顯示時中間有空白，可直接貼上）。"
                    + "請勿填 Google 帳戶的登入密碼");
        }
        return s;
    }

    /** 整封郵件的寄送時限（秒）；超過就中斷連線（v1.8.0）。測試時可縮短。 */
    static long totalTimeoutSec = 120;
    /** SMTP 回應單行長度上限。 */
    static final int MAX_LINE = 2000;

    public void send(String from, String appPassword, List<String> toIn, String subject, String body) throws IOException {
        from = from == null ? "" : from;
        validateAddress(from);
        List<String> to = new java.util.ArrayList<>();
        for (String t : toIn) { String x = t == null ? "" : t.trim(); validateAddress(x); to.add(x); }   // 之後一律使用驗證過的值
        if (to.isEmpty()) throw new IllegalArgumentException("沒有設定收件人");
        String pw = normalizeAppPassword(appPassword);

        Socket s;
        if (plainForTest) {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 15000);
        } else {
            SSLSocket ss = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ss.connect(new InetSocketAddress(host, port), 15000);
            SSLParameters p = ss.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");     // 驗證憑證上的主機名稱（SSLSocket 預設不驗證）
            ss.setSSLParameters(p);
            ss.setSoTimeout(30000);
            ss.startHandshake();
            s = ss;
        }
        s.setSoTimeout(30000);
        java.util.concurrent.ScheduledFuture<?> deadline = Fetcher.closeAfter(s, totalTimeoutSec);
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = sock.getOutputStream()) {
            expect(in, 220, "連線");
            cmd(out, "EHLO secnewsmonitor");
            expect(in, 250, "EHLO");
            String auth = Base64.getEncoder().encodeToString(("\0" + from + "\0" + pw).getBytes(StandardCharsets.UTF_8));
            cmd(out, "AUTH PLAIN " + auth);
            int code = read(in).code;
            if (code == 535 || code == 534) {
                throw new IOException("Gmail 登入失敗：帳號或應用程式密碼錯誤（需開啟兩步驟驗證並建立應用程式密碼）");
            }
            if (code != 235) throw new IOException("Gmail 登入失敗（SMTP " + code + "）");
            cmd(out, "MAIL FROM:<" + from + ">");
            expect(in, 250, "寄件人");
            for (String t : to) {
                cmd(out, "RCPT TO:<" + t + ">");
                Reply r = read(in);
                if (r.code != 250 && r.code != 251) throw new IOException("收件人 " + t + " 被拒絕（SMTP " + r.code + "）");
            }
            cmd(out, "DATA");
            expect(in, 354, "DATA");
            out.write(message(from, to, subject, body).getBytes(StandardCharsets.US_ASCII));
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            expect(in, 250, "寄出");
            cmd(out, "QUIT");
        } catch (IOException e) {
            if (deadline.isDone()) throw new IOException("Gmail 寄送超過 " + totalTimeoutSec + " 秒，已中斷", e);
            throw e;
        } finally {
            deadline.cancel(false);
        }
    }

    /** 組成郵件（標頭以 RFC 2047 編碼、內文 base64），全部為 ASCII，不會有標頭注入或「.」開頭的行。 */
    static String message(String from, List<String> to, String subject, String body) {
        StringBuilder m = new StringBuilder();
        m.append("From: SecNewsMonitor <").append(from).append(">\r\n");
        m.append("To: ").append(String.join(", ", to)).append("\r\n");
        m.append("Subject: ").append(encodeHeader(ChatText.oneLine(subject, 150))).append("\r\n");
        m.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
        m.append("Message-ID: <").append(java.util.UUID.randomUUID()).append("@secnewsmonitor>\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: text/plain; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String b64 = Base64.getEncoder().encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < b64.length(); i += 76) m.append(b64, i, Math.min(b64.length(), i + 76)).append("\r\n");
        return m.toString();
    }

    /** 主旨以 UTF-8 base64 編碼，每段不超過 45 位元組（不會切在字的中間）。 */
    static String encodeHeader(String s) {
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String c = new String(Character.toChars(cp));
            int n = c.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > 45 && chunk.length() > 0) {
                out.append(out.length() > 0 ? "\r\n " : "").append(word(chunk.toString()));
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.append(c);
            bytes += n;
            i += Character.charCount(cp);
        }
        if (chunk.length() > 0) out.append(out.length() > 0 ? "\r\n " : "").append(word(chunk.toString()));
        return out.toString();
    }

    private static String word(String s) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private record Reply(int code, String text) {}

    private static void cmd(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static Reply read(BufferedReader in) throws IOException {
        String line;
        StringBuilder text = new StringBuilder();
        int lines = 0;
        while (true) {
            line = readLine(in);
            if (line == null) throw new IOException("Gmail 伺服器中斷連線");
            if (line.length() < 3 || ++lines > 100) throw new IOException("Gmail 伺服器回應格式不符");
            text.append(line.length() > 4 ? line.substring(4) : "").append(' ');
            if (line.length() == 3 || line.charAt(3) == ' ') break;
        }
        int code;
        try { code = Integer.parseInt(line.substring(0, 3)); } catch (NumberFormatException e) { throw new IOException("Gmail 伺服器回應格式不符"); }
        return new Reply(code, text.toString().trim());
    }

    /** 讀一行，超過 MAX_LINE 字元就中止（v1.8.0）。 */
    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > MAX_LINE) throw new IOException("Gmail 伺服器回應過長");
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void expect(BufferedReader in, int code, String step) throws IOException {
        Reply r = read(in);
        if (r.code != code) {
            throw new IOException("Gmail " + step + "失敗（SMTP " + r.code + "：" + ChatText.oneLine(r.text, 150) + "）");
        }
    }
}

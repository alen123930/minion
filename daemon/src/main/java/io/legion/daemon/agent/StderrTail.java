package io.legion.daemon.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * stderr 有界尾巴（参照仓库 stderr_tail.go）：CLI 在吐出结构化错误前崩溃
 * （V8 abort、Bun panic、OOM）时，exit status 单独不可诊断——把最后几 KB
 * 拼进失败消息才能定位。同时剥控制字符与常见密钥形态，别把 Authorization
 * 头持久化进任务错误字段。
 */
final class StderrTail {

    /** 2KiB：装得下典型 CLI 错误行，又不至于撑爆 Result.Error。 */
    static final int TAIL_BYTES = 2048;

    private static final Pattern AUTHORIZATION_HEADER =
            Pattern.compile("(?im)(authorization\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern JSON_SECRET =
            Pattern.compile("(?i)(\"(?:token|auth|authorization|api[_-]?key|secret|password)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"");

    private final int max;
    private final Deque<Byte> buf = new ArrayDeque<>();
    private long total;

    StderrTail() {
        this(TAIL_BYTES);
    }

    StderrTail(int max) {
        this.max = max;
    }

    synchronized void append(byte[] bytes, int len) {
        total += len;
        for (int i = 0; i < len; i++) {
            buf.addLast(bytes[i]);
            if (buf.size() > max) {
                buf.removeFirst();
            }
        }
    }

    synchronized long totalBytes() {
        return total;
    }

    /** 有效 UTF-8 + 去密钥 + trim；空串 = 无可诊断内容。 */
    synchronized String tail() {
        if (buf.isEmpty()) {
            return "";
        }
        byte[] raw = new byte[buf.size()];
        int i = 0;
        for (byte b : buf) {
            raw[i++] = b;
        }
        String s = new String(raw, StandardCharsets.UTF_8);
        // 字节边界截断可能留下半个多字节字符：把替换字符与控制字符一起清掉
        s = s.replaceAll("[\\uFFFD\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        s = AUTHORIZATION_HEADER.matcher(s).replaceAll("$1[REDACTED]");
        s = JSON_SECRET.matcher(s).replaceAll("$1\"[REDACTED]\"");
        return s.trim();
    }

    /** withAgentStderr：非空才拼，保持原始消息可读。 */
    static String attach(String message, String provider, String tail) {
        if (tail == null || tail.isEmpty()) {
            return message;
        }
        return message + "; " + provider + " stderr: " + tail;
    }
}

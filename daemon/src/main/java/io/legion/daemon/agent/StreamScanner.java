package io.legion.daemon.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * agent 事件流的按行读取器，行长度上限的全局唯一定义处。
 *
 * <p>32MiB 的出处：GH #4520 / MUL-5722——旧 10MiB 上限让 Codex 把整段会话塞进单行
 * thread/resume 时永久失败；上调到 32MiB 是余量不是保证，恢复路径比数字重要。
 * 治理教训：上限曾在各适配器复制粘贴后漂移、修复只到达其中一个，所以每个读
 * 行式 agent 传输的 backend 必须走这一个类，不许各自 new BufferedReader。
 *
 * <p>越界 fail-closed：抛 {@link LineTooLongException} 而不是截断——截断会造出
 * 半个 JSON 行，下游只能报"解析失败"，真正的故障（事件超长）被掩盖。
 * 参照仓库 stream_scanner.go（bufio.Scanner + ErrTooLong 语义）。
 */
public final class StreamScanner implements AutoCloseable {

    /** 全局唯一的行长度上限：32MiB。 */
    public static final int DEFAULT_MAX_LINE_BYTES = 32 * 1024 * 1024;

    /** 初始缓冲 64KiB，行内可增长到上限；普通事件不触发扩容拷贝。 */
    private static final int INITIAL_BUFFER_BYTES = 64 * 1024;

    private final InputStream in;
    private final int maxLineBytes;
    private byte[] buf = new byte[INITIAL_BUFFER_BYTES];
    private int len;
    private boolean eof;

    public StreamScanner(InputStream in) {
        this(in, DEFAULT_MAX_LINE_BYTES);
    }

    /** 供测试注入小上限；生产路径一律用 {@link #DEFAULT_MAX_LINE_BYTES}。 */
    StreamScanner(InputStream in, int maxLineBytes) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * 读下一行（不含换行符；\r\n 剥掉 \r）。EOF 返回 null；
     * 最后一行无换行符也正常返回。空行返回 ""。
     */
    public String readLine() throws IOException {
        len = 0;
        boolean sawAny = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                eof = true;
                if (!sawAny) {
                    return null;
                }
                return finishLine();
            }
            sawAny = true;
            if (b == '\n') {
                return finishLine();
            }
            if (len == maxLineBytes) {
                // 读到 cap+1 字节仍未见换行即失败，绝不静默截断
                throw new LineTooLongException(maxLineBytes + 1);
            }
            if (len == buf.length) {
                buf = Arrays.copyOf(buf, Math.min(buf.length * 2, Math.max(maxLineBytes, buf.length)));
            }
            buf[len++] = (byte) b;
        }
    }

    private String finishLine() {
        int end = len;
        if (end > 0 && buf[end - 1] == '\r') {
            end--;
        }
        return new String(buf, 0, end, StandardCharsets.UTF_8);
    }

    public boolean isEof() {
        return eof;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** 单行超过上限（attemptsBytes = 判定失败时已读字节数，至少 cap+1）。 */
    public static final class LineTooLongException extends IOException {

        private final long attemptedBytes;

        public LineTooLongException(long attemptedBytes) {
            super("agent stream line exceeds " + DEFAULT_MAX_LINE_BYTES
                    + " byte cap (read at least " + attemptedBytes + " bytes without a newline)");
            this.attemptedBytes = attemptedBytes;
        }

        public long attemptedBytes() {
            return attemptedBytes;
        }
    }
}

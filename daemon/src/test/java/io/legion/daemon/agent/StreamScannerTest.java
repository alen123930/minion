package io.legion.daemon.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行长度上限的 fail-closed 契约（GH #4520 / MUL-5722）：
 * 上限曾被各适配器复制粘贴后漂移，修复只到达其中一个——所以上限只许在
 * StreamScanner 一处定义，且越界必须报错而不是静默截断（截断会造出半个 JSON 行）。
 */
class StreamScannerTest {

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void defaultCapIs32MiBDefinedOnce() {
        // 10MiB 旧上限回归（MUL-5722：Codex 把整线程塞进单行 thread/resume）：
        // 上限必须大于旧 10MiB，否则长会话 resume 永久失败
        assertTrue(StreamScanner.DEFAULT_MAX_LINE_BYTES > 10 * 1024 * 1024);
        assertEquals(32 * 1024 * 1024, StreamScanner.DEFAULT_MAX_LINE_BYTES);
    }

    @Test
    void readsLinesAndHandlesCrLfAndBlankAndEofWithoutNewline() throws IOException {
        StreamScanner scanner = new StreamScanner(
                bytes("{\"type\":\"system\"}\r\n\r\n{\"type\":\"assistant\"}\n{\"type\":\"result\"}"));
        assertEquals("{\"type\":\"system\"}", scanner.readLine());
        assertEquals("", scanner.readLine());
        assertEquals("{\"type\":\"assistant\"}", scanner.readLine());
        assertEquals("{\"type\":\"result\"}", scanner.readLine());
        assertNull(scanner.readLine());
    }

    @Test
    void lineAtCapBoundaryIsAllowedOneByteOverFails() throws IOException {
        int cap = 1024;
        String atCap = "x".repeat(cap);
        StreamScanner ok = new StreamScanner(bytes(atCap + "\n"), cap);
        assertEquals(atCap, ok.readLine());

        StreamScanner over = new StreamScanner(bytes("x".repeat(cap + 1) + "\n"), cap);
        StreamScanner.LineTooLongException e = assertThrows(
                StreamScanner.LineTooLongException.class, over::readLine);
        assertEquals(cap + 1, e.attemptedBytes());
    }

    @Test
    void oversizeLineFailsInsteadOfBufferingToOom() {
        // 4GiB 无换行流：不能先全读进内存再判定——读到 cap+1 字节就要立刻失败
        int cap = 4096;
        InputStream endless = new InputStream() {
            private long sent;

            @Override
            public int read() {
                sent++;
                return 'x';
            }

            @Override
            public void close() {
            }
        };
        StreamScanner scanner = new StreamScanner(endless, cap);
        assertThrows(StreamScanner.LineTooLongException.class, scanner::readLine);
    }

    @Test
    void utf8MultibyteLineSurvives() throws IOException {
        String line = "{\"text\":\"你好世界\"}";
        StreamScanner scanner = new StreamScanner(bytes(line + "\n"));
        assertEquals(line, scanner.readLine());
    }
}

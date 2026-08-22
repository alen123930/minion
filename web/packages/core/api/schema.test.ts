import { afterEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { noopLogger, type Logger } from "../logger";
import { parseWithFallback, setSchemaLogger } from "./schema";

afterEach(() => {
  setSchemaLogger(noopLogger);
});

describe("parseWithFallback", () => {
  it("payload 符合 schema 时返回解析结果", () => {
    const schema = z.object({ id: z.number(), title: z.string() });
    const fallback = { id: 0, title: "" };
    const parsed = parseWithFallback(
      { id: 1, title: "你好" },
      schema,
      fallback,
      { endpoint: "GET /api/issues" },
    );
    expect(parsed).toEqual({ id: 1, title: "你好" });
  });

  it("payload 不符时返回 fallback 而非抛异常", () => {
    const schema = z.object({ id: z.number() });
    const fallback = { id: 0 };
    const parsed = parseWithFallback(
      { id: "不是数字" },
      schema,
      fallback,
      { endpoint: "GET /api/issues" },
    );
    expect(parsed).toEqual({ id: 0 });
  });

  it("校验失败时以 endpoint 标识记录警告（不抛白屏）", () => {
    const warn = vi.fn();
    const logger: Logger = {
      debug: vi.fn(),
      info: vi.fn(),
      warn,
      error: vi.fn(),
    };
    setSchemaLogger(logger);
    const schema = z.object({ id: z.number() });
    parseWithFallback({ id: "x" }, schema, { id: 0 }, { endpoint: "GET /api/issues" });
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledWith(
      expect.stringContaining("GET /api/issues"),
      expect.objectContaining({ endpoint: "GET /api/issues" }),
    );
  });

  it("宽松 schema（z.string() 枚举）容忍未知枚举值", () => {
    const schema = z.object({ status: z.string() });
    const fallback = { status: "unknown" };
    const parsed = parseWithFallback(
      { status: "brand-new-status" },
      schema,
      fallback,
      { endpoint: "GET /api/issues" },
    );
    expect(parsed).toEqual({ status: "brand-new-status" });
  });
});
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Button } from "./button";

afterEach(() => {
  cleanup();
});

describe("Button", () => {
  it("渲染标签文本", () => {
    render(<Button>保存</Button>);
    expect(screen.getByRole("button", { name: "保存" })).toBeTruthy();
  });

  it("点击时触发 onClick", () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>保存</Button>);
    screen.getByRole("button", { name: "保存" }).click();
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
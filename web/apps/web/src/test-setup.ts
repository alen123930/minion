// jsdom 与 Node 属两个 realm：react-router navigate() 内部用 jsdom 的
// AbortController 产 signal 并 new Request(url, {signal})，而全局 Request
// 是 Node/undici 的，instanceof 校验不过，抛
// "RequestInit: Expected signal to be an instance of AbortSignal"。
// 测试环境用剥掉 signal 的 Request 子类对齐（路由仅用它做内部中断记账，
// 测试不依赖该语义；浏览器同 realm 无此问题）。
// 直接 defineProperty 而非 vi.stubGlobal：测试的 afterEach 会调
// vi.unstubAllGlobals()，只回滚 stubGlobal 登记的键，不应波及本对齐。
const NativeRequest = globalThis.Request;

class LenientRequest extends NativeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(input, init?.signal !== undefined ? { ...init, signal: undefined } : init);
  }
}

Object.defineProperty(globalThis, "Request", {
  value: LenientRequest,
  writable: true,
  configurable: true,
});

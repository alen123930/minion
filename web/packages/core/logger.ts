type LogLevel = "debug" | "info" | "warn" | "error";

const CONSOLE_METHOD: Record<LogLevel, "log" | "info" | "warn" | "error"> = {
  debug: "log",
  info: "info",
  warn: "warn",
  error: "error",
};

export interface Logger {
  debug(msg: string, ...data: unknown[]): void;
  info(msg: string, ...data: unknown[]): void;
  warn(msg: string, ...data: unknown[]): void;
  error(msg: string, ...data: unknown[]): void;
}

export function createLogger(namespace: string): Logger {
  const make =
    (level: LogLevel) =>
    (msg: string, ...data: unknown[]) => {
      const ts = new Date().toISOString().slice(11, 23);
      const prefix = `[${ts}] [${namespace}]`;
      if (data.length > 0) {
        console[CONSOLE_METHOD[level]](`${prefix} ${msg}`, ...data);
      } else {
        console[CONSOLE_METHOD[level]](`${prefix} ${msg}`);
      }
    };

  return {
    debug: make("debug"),
    info: make("info"),
    warn: make("warn"),
    error: make("error"),
  };
}

/** No-op logger for when logging is not needed. */
export const noopLogger: Logger = {
  debug() {},
  info() {},
  warn() {},
  error() {},
};
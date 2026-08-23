package io.legion.contracts.agent;

/** 一次执行的完整入参：prompt + 协商面（设计 §6.1）。 */
public record ExecRequest(String prompt, ExecOptions options) {

    public ExecRequest {
        if (options == null) {
            options = ExecOptions.defaults();
        }
    }

    public ExecRequest(String prompt) {
        this(prompt, ExecOptions.defaults());
    }
}

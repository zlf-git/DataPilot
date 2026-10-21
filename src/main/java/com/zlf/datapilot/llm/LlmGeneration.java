package com.zlf.datapilot.llm;

import com.zlf.datapilot.ir.QueryIr;

/**
 * LLM 生成结果：结构化 IR + token 用量（AgentOps 成本统计依赖 token）。
 */
public class LlmGeneration {

    private QueryIr ir;

    private int promptTokens;

    private int completionTokens;

    private int totalTokens;

    public QueryIr getIr() {
        return ir;
    }

    public void setIr(QueryIr ir) {
        this.ir = ir;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}

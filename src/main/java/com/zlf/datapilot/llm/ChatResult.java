package com.zlf.datapilot.llm;

/**
 * LLM 对话结果：文本 + token 用量。
 * 对应 OpenAI 兼容响应里的 choices[0].message.content 与 usage 字段。
 */
public class ChatResult {

    /** 模型返回文本 */
    private String content;

    /** 输入 token（prompt） */
    private int promptTokens;

    /** 输出 token（completion） */
    private int completionTokens;

    /** 总 token */
    private int totalTokens;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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

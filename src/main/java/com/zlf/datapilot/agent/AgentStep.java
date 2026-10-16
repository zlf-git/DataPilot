package com.zlf.datapilot.agent;

/**
 * Agent 步骤节点接口：每个节点负责一件事，输入输出都是 AgentContext。
 * 借鉴 DataAgent 的多节点编排思想（规划→生成→校验→执行→解释）。
 */
public interface AgentStep {

    /** 节点名，用于日志和调试 */
    String name();

    /** 执行本节点逻辑，修改并返回上下文 */
    AgentContext execute(AgentContext ctx);
}

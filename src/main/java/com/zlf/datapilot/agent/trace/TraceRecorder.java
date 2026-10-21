package com.zlf.datapilot.agent.trace;

import com.zlf.datapilot.config.DatapilotProperties;
import org.springframework.stereotype.Component;

/**
 * 埋点器工厂（Ticket 09）：一次 ask 一个 {@link TraceStep}。
 *
 * <p>单独一个 bean 而不是让 TraceStep 自己当 bean：TraceStep 是<b>有状态</b>的
 * （span 序号、运行开始时间），做成单例就会把并发请求的 trace 串在一起。
 */
@Component
public class TraceRecorder {

    private final AgentTraceRepository repo;
    private final DatapilotProperties props;

    public TraceRecorder(AgentTraceRepository repo, DatapilotProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public TraceStep open(String traceId) {
        return new TraceStep(repo, props.getCost(), traceId);
    }
}

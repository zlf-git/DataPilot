package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.knowledge.Recaller;
import com.zlf.datapilot.knowledge.model.RecalledMetadata;
import com.zlf.datapilot.sql.TimeAnchorProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * ② 元数据召回节点：找跟问题相关的表、字段、指标、术语、few-shot。
 *
 * <p>Ticket 04 起顺带预热<b>时间锚点</b>（各候选表的 {@code MAX(dt)}）：一次问数只查一次库，
 * 后面 IR→SQL 展开「近 N 天」窗口时直接命中缓存。预热失败只记 warn —— 真正需要锚点时
 * 生成器会显式报错，不会悄悄退回 now()。
 */
@Component
public class RecallStep implements AgentStep {

    private final Recaller recaller;
    private final TimeAnchorProvider anchorProvider;

    public RecallStep(Recaller recaller, TimeAnchorProvider anchorProvider) {
        this.recaller = recaller;
        this.anchorProvider = anchorProvider;
    }

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        String question = ctx.getRewrittenQuestion() != null
                ? ctx.getRewrittenQuestion()
                : ctx.getUserQuestion();
        RecalledMetadata meta = recaller.recall(question);
        ctx.setRecalledMetadata(meta);
        ctx.setRelatedTables(new ArrayList<>(meta.getDdlMap().keySet()));
        ctx.getRelatedTables().forEach(anchorProvider::prefetchQuietly);
        return ctx;
    }
}

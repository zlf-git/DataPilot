package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentEngine;
import com.zlf.datapilot.agent.hitl.PreviewStore;
import com.zlf.datapilot.agent.trace.RunStatus;
import com.zlf.datapilot.ir.QueryIr;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HITL 两段契约（Ticket 12）：先预览、再确认执行。
 *
 * <ul>
 *   <li>{@code POST /api/ask/preview} {question, sessionId?} → 生成并校验 SQL，<b>不执行</b>，
 *       返回 {@code previewId} 与四道闸门的校验结果；</li>
 *   <li>{@code POST /api/ask/execute} {previewId, ir?} → 按快照执行，<b>重跑闸门 1~4</b>。
 *       {@code ir} 是「对预览 IR 的编辑版」，允许改的只有过滤值 / limit / 排序方向 / 维度；
 *       越界即 {@code status=REJECTED} 且一条 SQL 都不发。</li>
 * </ul>
 *
 * <p>为什么执行只接受「快照 + 增量编辑」而不是「一份完整的 IR」：完整 IR 意味着客户端
 * 可以拿一份和预览毫无关系的查询来执行，那预览时的四道闸门就等于没校验过。
 * 快照留在服务端，编辑由 {@code IrEditGuard} 在服务端逐字段判定。
 *
 * <p>另一个刻意的取舍：{@code /api/ask} 保持原样（一步到底、不强制预览）。
 * 强制两段会让「就想问个数」的场景多一次往返，HITL 的价值在「要跑一条影响面较大的查询时先看一眼」，
 * 不是所有问数都得点两次。
 */
@RestController
@RequestMapping("/api/ask")
public class HitlController {

    private final AgentEngine engine;
    private final PreviewStore previews;

    public HitlController(AgentEngine engine, PreviewStore previews) {
        this.engine = engine;
        this.previews = previews;
    }

    @PostMapping("/preview")
    public AskResponse preview(@RequestBody AskController.AskRequest request) {
        long start = System.currentTimeMillis();
        AgentContext ctx = engine.preview(request.getQuestion(), request.getSessionId());
        return AskController.toResponse(ctx, System.currentTimeMillis() - start);
    }

    @PostMapping("/execute")
    public AskResponse execute(@RequestBody ExecuteRequest request) {
        PreviewStore.Preview snapshot = previews.get(request.getPreviewId());
        if (snapshot == null) {
            return expired(request.getPreviewId());
        }
        long start = System.currentTimeMillis();
        AgentContext ctx = engine.executePreview(snapshot, request.getIr());
        return AskController.toResponse(ctx, System.currentTimeMillis() - start);
    }

    /**
     * 预览已过期 / 不存在。这条路径<b>不落 agent_run</b>：它没有跑任何东西，
     * 连问题都是空的（快照才是问题的来源），写一条空运行记录只会污染统计。
     */
    private AskResponse expired(String previewId) {
        AskResponse r = new AskResponse();
        r.setStatus(RunStatus.FAILED.name());
        r.setQuestion("");
        r.setPreviewId(previewId);
        r.setGates(List.of());
        r.setParams(List.of());
        r.setRows(List.of());
        r.setEdits(List.of());
        r.setStructuralChanges(List.of());
        r.setChartType("table");
        r.setReportHtml("");
        r.setAnswer("预览不存在或已过期（" + previews.ttlMinutes() + " 分钟有效），请重新预览后再执行");
        return r;
    }

    /** 执行请求体：previewId 必填；ir 可选（不传 = 按预览原样执行） */
    public static class ExecuteRequest {

        private String previewId;

        /** 编辑后的 IR（缺省/为空 = 不编辑）。字段级合法性由服务端 diff 判定，不看客户端自述 */
        private QueryIr ir;

        public String getPreviewId() {
            return previewId;
        }

        public void setPreviewId(String previewId) {
            this.previewId = previewId;
        }

        public QueryIr getIr() {
            return ir;
        }

        public void setIr(QueryIr ir) {
            this.ir = ir;
        }
    }
}

package com.zlf.datapilot.controller;

import com.zlf.datapilot.agent.trace.AgentTraceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentOps 查询接口（Ticket 09）。
 * <ul>
 *   <li>{@code GET /api/traces?limit=20&sessionId=xxx}：最近运行列表（id 倒序 = 时间倒序）；</li>
 *   <li>{@code GET /api/traces/{traceId}}：单次运行 + 按 step_order 排序的 span 明细。</li>
 * </ul>
 *
 * <p>只读接口，不参与问数链路；查不到 traceId 返回 404 而不是空对象 —— 前端（Ticket 13）
 * 拿到空对象会渲染出一张空 trace，比直接报「没有这次运行」更难排查。
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    /** 单页上限：trace 是排障用的，翻历史请用 sessionId 过滤而不是把 limit 调大 */
    private static final int MAX_LIMIT = 200;

    private final AgentTraceRepository repo;

    public TraceController(AgentTraceRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String sessionId) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Map<String, Object>> items = repo.listRuns(safeLimit, sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("limit", safeLimit);
        out.put("sessionId", sessionId);
        out.put("items", items);
        return out;
    }

    @GetMapping("/{traceId}")
    public Map<String, Object> detail(@PathVariable String traceId) {
        Map<String, Object> run = repo.findRun(traceId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这次运行记录：" + traceId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("steps", repo.listSteps(traceId));
        return out;
    }
}

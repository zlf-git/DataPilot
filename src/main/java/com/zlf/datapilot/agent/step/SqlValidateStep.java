package com.zlf.datapilot.agent.step;

import com.zlf.datapilot.agent.AgentContext;
import com.zlf.datapilot.agent.AgentStep;
import com.zlf.datapilot.ir.Filter;
import com.zlf.datapilot.knowledge.MetadataRepository;
import com.zlf.datapilot.knowledge.model.FieldMetadata;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * ④ 校验节点（五道闸门的最小实现：存在性闸门·字段白名单）。
 * Ticket 01 只堵最要命的列位注入点：filter.field 来自 LLM 输出的 IR，若直拼进 SQL，
 * 真 LLM 输出 field="dt = ? UNION SELECT ..." 就是列位注入，value 参数化挡不住列名拼接。
 * 此处校验每个 filter 的 field 必须落在 field_metadata 白名单，否则拒绝并停止链路。
 * 完整五道闸门（存在性/权限/代价/语法/结果自检）由 Ticket 02 落地。
 */
@Component
public class SqlValidateStep implements AgentStep {

    private final MetadataRepository repo;

    public SqlValidateStep(MetadataRepository repo) {
        this.repo = repo;
    }

    @Override
    public String name() {
        return "validate";
    }

    @Override
    public AgentContext execute(AgentContext ctx) {
        if (ctx.getIr() == null || ctx.getIr().getFilters() == null || ctx.getIr().getFilters().isEmpty()) {
            return ctx;
        }

        Set<String> whitelist = repo.listAllFields().stream()
                .map(FieldMetadata::getFieldName)
                .collect(Collectors.toSet());

        for (Filter f : ctx.getIr().getFilters()) {
            String field = f.getField();
            if (field == null || !whitelist.contains(field)) {
                ctx.setErrorMessage("字段不在白名单，已拒绝：" + field);
                return ctx;
            }
        }
        return ctx;
    }
}

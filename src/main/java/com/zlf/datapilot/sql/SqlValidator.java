package com.zlf.datapilot.sql;

import com.zlf.datapilot.knowledge.OntologyCache;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 语法校验器（语法闸门核心）：JSqlParser 解析 + 表/字段二次提取比对白名单。
 * 双重价值：① 语法合法性；② 即使 IR 层漏过，SQL 层的表/字段提取再次兜底，幻觉归零第二道防线。
 */
@Component
public class SqlValidator {

    private final OntologyCache cache;

    public SqlValidator(OntologyCache cache) {
        this.cache = cache;
    }

    /**
     * 校验 SQL：解析失败或表/字段引用越界即 fail。
     * 注意：SQL 里的 value 已参数化（? 占位符），JSqlParser 解析 JdbcParameter 无碍。
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.fail("SQL 为空");
        }

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            return ValidationResult.fail("SQL 语法错误：" + brief(e));
        }

        // 表名二次比对
        List<String> tables = new TablesNamesFinder().getTableList(stmt);
        for (String t : tables) {
            String name = stripSchema(t);
            if (!cache.tables().contains(name)) {
                return ValidationResult.fail("SQL 引用了白名单外的表：" + name);
            }
        }

        // 字段名二次比对
        // 注意：ORDER BY 里可能引用 SELECT 的别名（如 ORDER BY gmv_channel），别名不是表字段，
        // 必须排除，否则 Ticket 03 的「按聚合别名排序」会被自己的语法闸门拦死。
        Set<String> aliases = stmt instanceof PlainSelect ps ? selectAliases(ps) : Set.of();
        Set<String> columns = extractColumns(stmt);
        for (String c : columns) {
            if (aliases.contains(c)) {
                continue;
            }
            if (!cache.fields().contains(c)) {
                return ValidationResult.fail("SQL 引用了白名单外的字段：" + c);
            }
        }

        return ValidationResult.ok(sql);
    }

    /** SELECT 项的别名集合（别名由生成器产生，非用户可控，不需要再过字段白名单） */
    private Set<String> selectAliases(PlainSelect ps) {
        Set<String> aliases = new HashSet<>();
        for (SelectItem<?> item : ps.getSelectItems()) {
            if (item.getAlias() != null && item.getAlias().getName() != null) {
                aliases.add(item.getAlias().getName());
            }
        }
        return aliases;
    }

    /** 提取 SQL 里出现的所有列名（SELECT 项 / WHERE / GROUP BY / ORDER BY / HAVING） */
    private Set<String> extractColumns(Statement stmt) {
        Set<String> columns = new HashSet<>();
        if (!(stmt instanceof PlainSelect ps)) {
            return columns;
        }
        ExpressionVisitorAdapter collector = new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                columns.add(column.getColumnName());
            }
        };

        for (SelectItem<?> item : ps.getSelectItems()) {
            Expression e = item.getExpression();
            if (e != null) {
                e.accept(collector);
            }
        }
        if (ps.getWhere() != null) {
            ps.getWhere().accept(collector);
        }
        if (ps.getGroupBy() != null) {
            ExpressionList groupBy = ps.getGroupBy().getGroupByExpressionList();
            if (groupBy != null) {
                groupBy.accept(collector);
            }
        }
        if (ps.getHaving() != null) {
            ps.getHaving().accept(collector);
        }
        if (ps.getOrderByElements() != null) {
            for (OrderByElement ob : ps.getOrderByElements()) {
                ob.getExpression().accept(collector);
            }
        }
        return columns;
    }

    /** 去掉 schema 前缀：mall_dw.ads_x → ads_x */
    private String stripSchema(String table) {
        if (table == null) {
            return "";
        }
        int idx = table.lastIndexOf('.');
        return idx >= 0 ? table.substring(idx + 1) : table;
    }

    private String brief(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 160 ? msg.substring(0, 160) + "..." : msg;
    }
}

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
元数据导出（Ticket 19）：把**活库当前实况**导成可重放的 SQL。

为什么需要它：`deploy/seed.sql` 是手写的基线（4 张表 / 28 字段 / 13 指标 / 5 条闸门阈值），
它只能保证「基线那几条在」，不能保证「活库里新增的表也在」。新环境重建时如果只跑 seed，
新增的表就丢了，`/api/health/drift` 会立刻报 inDbNotInOntology —— 评审把这条列为
「加表零改动」的四号缺口。

用法：
    python qa/dump_metadata.py http://127.0.0.1:18080 > deploy/seed_from_live.sql
    python qa/dump_metadata.py http://127.0.0.1:18080 -o deploy/seed_from_live.sql

生成的 SQL 全部是 INSERT ... ON DUPLICATE KEY UPDATE，可以重复执行、不会重复插。
只读接口，不需要凭证，不写库。

注意两点（别当它是 pg_dump）：
  * 导的是**语义层四张表**（semantic_model / field_metadata / metric_def / gate_config），
    不导运行数据（agent_run / agent_step / few_shot_example）；
  * metric_def 用 ON DUPLICATE KEY UPDATE 全列覆盖（与 seed.sql 的口径收敛策略一致），
    semantic_model / field_metadata 只补缺（INSERT IGNORE），避免运维手工调的注释被导回覆盖。
"""
import json
import sys
import urllib.error
import urllib.request

DEFAULT_HOST = 'http://127.0.0.1:18080'


def call(host, path):
    req = urllib.request.Request(host + path)
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read().decode('utf-8')
    return json.loads(raw) if raw.strip() else None


def q(v):
    """SQL 字面量。None → NULL；字符串转义单引号与反斜杠。"""
    if v is None or v == '':
        return 'NULL'
    if isinstance(v, bool):
        return '1' if v else '0'
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v).replace('\\', '\\\\').replace("'", "''")
    return "'%s'" % s


def main():
    args = [a for a in sys.argv[1:]]
    out_path = None
    if '-o' in args:
        i = args.index('-o')
        out_path = args[i + 1]
        del args[i:i + 2]
    host = (args[0] if args else DEFAULT_HOST).rstrip('/')

    try:
        tables = call(host, '/api/metadata/tables') or []
        fields = call(host, '/api/metadata/fields') or []
        metrics = call(host, '/api/metrics') or []
        gates = call(host, '/api/gate-config') or []
    except (urllib.error.URLError, ConnectionError) as e:
        print('-- 服务不可达：%s（%s）' % (host, e))
        sys.exit(2)

    lines = []
    add = lines.append
    add('-- ============================================================')
    add('-- DataPilot 元数据导出（由 qa/dump_metadata.py 生成，勿手工编辑）')
    add('-- 来源实例：%s' % host)
    add('-- 规模：表 %d / 字段 %d / 指标 %d / 闸门阈值 %d' % (len(tables), len(fields), len(metrics), len(gates)))
    add('-- 用法：mysql -h127.0.0.1 -P3307 -uroot -proot123 data_agent --default-character-set=utf8mb4 < 本文件')
    add('-- 幂等：semantic_model / field_metadata 用 INSERT IGNORE（不覆盖人工改过的注释），')
    add('--       metric_def / gate_config 用 ON DUPLICATE KEY UPDATE（口径以本文件为准）')
    add('-- ============================================================')
    add('SET NAMES utf8mb4;')
    add('')

    add('-- 1. 语义模型（%d 张表）' % len(tables))
    if tables:
        add('INSERT IGNORE INTO semantic_model (table_name, table_comment, biz_domain) VALUES')
        rows = [
            '(%s, %s, %s)' % (q(t.get('tableName')), q(t.get('tableComment')), q(t.get('bizDomain')))
            for t in tables
        ]
        add(',\n'.join(rows) + ';')
    add('')

    add('-- 2. 字段元数据（%d 个字段）' % len(fields))
    if fields:
        add('INSERT IGNORE INTO field_metadata '
            '(table_name, field_name, field_type, field_comment, field_role, enum_values) VALUES')
        rows = [
            '(%s, %s, %s, %s, %s, %s)' % (
                q(f.get('tableName')), q(f.get('fieldName')), q(f.get('fieldType')),
                q(f.get('fieldComment')), q(f.get('fieldRole')), q(f.get('enumValues')))
            for f in fields
        ]
        add(',\n'.join(rows) + ';')
    add('')

    add('-- 3. 指标口径（%d 个指标，全列覆盖）' % len(metrics))
    if metrics:
        add('INSERT INTO metric_def')
        add('(metric_id, metric_name, metric_alias, department, metric_sql, metric_type, table_name, dimensions, '
            'owner, status, denominator_table, denominator_sql) VALUES')
        rows = [
            '(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)' % (
                q(m.get('metricId')), q(m.get('metricName')), q(m.get('metricAlias')),
                q(m.get('department')), q(m.get('metricSql')), q(m.get('metricType')),
                q(m.get('tableName')), q(m.get('dimensions')), q(m.get('owner')),
                q(m.get('status')), q(m.get('denominatorTable')), q(m.get('denominatorSql')))
            for m in metrics
        ]
        add(',\n'.join(rows))
        add('ON DUPLICATE KEY UPDATE')
        add('    metric_name = VALUES(metric_name), metric_alias = VALUES(metric_alias),')
        add('    department = VALUES(department), metric_sql = VALUES(metric_sql),')
        add('    metric_type = VALUES(metric_type), table_name = VALUES(table_name),')
        add('    dimensions = VALUES(dimensions), owner = VALUES(owner), status = VALUES(status),')
        add('    denominator_table = VALUES(denominator_table), denominator_sql = VALUES(denominator_sql);')
    add('')

    add('-- 4. 指标-表关联（从指标定义派生：主表 + 分母表）')
    pairs = []
    for m in metrics:
        if m.get('tableName'):
            pairs.append((m.get('metricId'), m.get('tableName')))
        if m.get('denominatorTable'):
            pairs.append((m.get('metricId'), m.get('denominatorTable')))
    if pairs:
        add('INSERT IGNORE INTO metric_table (metric_id, table_name) VALUES')
        add(',\n'.join('(%s, %s)' % (q(a), q(b)) for a, b in pairs) + ';')
    add('')

    add('-- 5. 闸门阈值（%d 条，全列覆盖）' % len(gates))
    if gates:
        add('INSERT INTO gate_config (gate_name, param_key, param_value, updated_by) VALUES')
        rows = [
            '(%s, %s, %s, %s)' % (q(g.get('gateName')), q(g.get('paramKey')),
                                  q(str(g.get('paramValue'))), q(g.get('updatedBy')))
            for g in gates
        ]
        add(',\n'.join(rows))
        add('ON DUPLICATE KEY UPDATE param_value = VALUES(param_value), updated_by = VALUES(updated_by);')
    add('')

    text = '\n'.join(lines)
    if out_path:
        with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        print('已写入 %s（表 %d / 字段 %d / 指标 %d / 闸门 %d）'
              % (out_path, len(tables), len(fields), len(metrics), len(gates)))
    else:
        sys.stdout.write(text)


if __name__ == '__main__':
    main()

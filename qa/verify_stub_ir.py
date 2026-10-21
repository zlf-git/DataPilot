# -*- coding: utf-8 -*-
"""
Ticket 01c 静态验收：15 个 stub_ir/q{n}.json 齐全 + 每条可反序列化为合法 QueryIr
+ metricId/field 落在 Ontology 白名单。

白名单不再手抄常量，改为启动服务后从运行时真值拉取（01c 测试报告 P1-4）：
  GET /api/metrics           → metricId 全集
  GET /api/metadata/fields   → field 全集
服务不可达时直接退出码 2（拒绝用过期常量给假绿）。

用法：python verify_stub_ir.py [host]   默认 http://127.0.0.1:18080
退出码：0 通过 / 1 断言失败 / 2 白名单取不到（服务未启动）
"""
import json
import os
import sys
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'

BASE = os.path.abspath(os.path.join(
    os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'stub_ir'))

VALID_OPS = {'EQ', 'IN', 'LIKE', 'GT', 'GE', 'LT', 'LE'}
VALID_MODES = {'AGG', 'DETAIL'}
VALID_WINDOWS = {'LAST_N_DAYS', 'LAST_DAYS', 'RECENT_DAYS'}


def get_json(path):
    with urllib.request.urlopen(HOST + path, timeout=10) as resp:
        return json.loads(resp.read().decode('utf-8'))


def load_whitelist():
    """从运行时真值取白名单；取不到就退出码 2，不给假绿"""
    try:
        metrics = get_json('/api/metrics')
        fields = get_json('/api/metadata/fields')
    except Exception as e:
        print('SKIP: 无法从 %s 取白名单真值（%s）' % (HOST, e))
        print('      先启动服务再跑本脚本：mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=18080')
        sys.exit(2)

    metric_ids = {m.get('metricId') for m in metrics if m.get('metricId')}
    # 字段接口返回「表×字段」行，按 fieldName 去重
    field_names = {f.get('fieldName') for f in fields if f.get('fieldName')}
    if not metric_ids or not field_names:
        print('SKIP: 白名单为空（metric=%d / field=%d），可能元数据库未初始化'
              % (len(metric_ids), len(field_names)))
        sys.exit(2)
    print('白名单取自运行时：%d 指标 / %d 唯一字段（%d 行）'
          % (len(metric_ids), len(field_names), len(fields)))
    return metric_ids, field_names


def check_ir(ir, metric_ids, field_names, orderby_style):
    assert isinstance(ir, dict), 'ir 必须是 JSON 对象'
    assert 'question' in ir and ir['question'].strip(), 'ir 缺 question'
    metrics = ir.get('metrics') or []
    dimensions = ir.get('dimensions') or []
    filters = ir.get('filters') or []
    order_by = ir.get('orderBy') or []
    assert isinstance(metrics, list), 'metrics 必须是数组'
    assert isinstance(dimensions, list), 'dimensions 必须是数组'
    assert isinstance(filters, list), 'filters 必须是数组'
    assert isinstance(order_by, list), 'orderBy 必须是数组'
    for m in metrics:
        assert m.get('metricId') in metric_ids, '指标不在库内白名单: %r' % m.get('metricId')

    # selectMode（Ticket 03）：AGG 必须带指标；DETAIL 必须无指标且至少 1 个维度
    mode = ir.get('selectMode')
    if mode is not None:
        assert mode in VALID_MODES, '非法 selectMode: %r' % mode
    effective = mode or ('AGG' if metrics else 'DETAIL')
    if effective == 'AGG':
        assert metrics, 'AGG 模式必须至少 1 个指标'
    else:
        assert not metrics, 'DETAIL 模式必须没有指标（当前 %d 个）' % len(metrics)
        assert dimensions, 'DETAIL 模式必须至少 1 个维度'

    # ratio（Ticket 03 引入的占比声明）：分子/分母都必须是真实指标
    ratio = ir.get('ratio')
    if ratio is not None:
        for key in ('numerator', 'denominator'):
            val = ratio.get(key)
            assert val in metric_ids, '占比 %s 不在指标白名单: %r' % (key, val)
    for d in dimensions:
        assert d.get('field') in field_names, '维度字段不在白名单: %r' % d.get('field')
    for f in filters:
        assert f.get('field') in field_names, '过滤字段不在白名单: %r' % f.get('field')
        assert f.get('op') in VALID_OPS, '非法 op: %r' % f.get('op')
        assert 'value' in f, 'filter 缺 value'
    for o in order_by:
        fld = o.get('field')
        # Ticket 03 契约：orderBy.field ∈ metricId ∪ 字段名（两种写法都合法，但必须记录用了哪种）
        assert fld in field_names or fld in metric_ids, '排序字段不在 metricId/字段白名单: %r' % fld
        assert o.get('direction') in ('asc', 'desc'), '非法排序方向: %r' % o.get('direction')
        orderby_style[fld] = 'metricId' if fld in metric_ids else 'field'
    limit = ir.get('limit')
    if limit is not None:
        assert isinstance(limit, int) and limit > 0, 'limit 必须正整数'

    # timeRange（Ticket 04）：近 N 天相对窗口；anchor 由数据 MAX(dt) 解析，stub 里不应预填
    tr = ir.get('timeRange')
    if tr is not None:
        assert isinstance(tr, dict), 'timeRange 必须是对象'
        win = tr.get('relativeWindow')
        assert win in VALID_WINDOWS, '非法 relativeWindow: %r' % win
        days = tr.get('days')
        assert isinstance(days, int) and 1 <= days <= 366, 'timeRange.days 必须 1~366，当前 %r' % days
        assert not tr.get('anchor'), 'timeRange.anchor 应由数据 MAX(dt) 回填，stub 不应预填'
        assert not any((f.get('field') or '').lower() == 'dt' for f in filters), \
            'timeRange 与 dt 过滤不能并存（生成器会显式拒绝）'


def main():
    if not os.path.isdir(BASE):
        print('FAIL: stub_ir 目录不存在:', BASE)
        sys.exit(1)

    metric_ids, field_names = load_whitelist()

    files = sorted(
        (f for f in os.listdir(BASE) if f.startswith('q') and f.endswith('.json')),
        key=lambda x: int(x[1:-5]))
    expected = ['q%d.json' % i for i in range(1, 16)]
    actual = [os.path.basename(f) for f in files]
    missing = [e for e in expected if e not in actual]
    assert not missing, '缺失文件: %s' % missing
    print('文件齐全：%d 个' % len(expected))

    orderby_style = {}
    used_metrics, used_fields = set(), set()
    for name in files:
        with open(os.path.join(BASE, name), encoding='utf-8') as fp:
            data = json.load(fp)
        assert 'question' in data and data['question'].strip(), '%s 缺 question' % name
        assert 'ir' in data, '%s 缺 ir' % name
        check_ir(data['ir'], metric_ids, field_names, orderby_style)
        ir = data['ir']
        used_metrics.update(m['metricId'] for m in (ir.get('metrics') or []))
        used_fields.update(d['field'] for d in (ir.get('dimensions') or []))
        used_fields.update(f['field'] for f in (ir.get('filters') or []))
        print('  OK %-8s %s' % (name, data['question']))

    print('\n实引用 %d 个指标 / %d 个字段，全部在库内白名单' % (len(used_metrics), len(used_fields)))
    if orderby_style:
        print('orderBy.field 写法分布（Ticket 03 需同时支持两种）：%s'
              % json.dumps(orderby_style, ensure_ascii=False))
    print('全部通过：%d/%d 条可反序列化且字段合法（白名单来自运行时）' % (len(files), len(expected)))


if __name__ == '__main__':
    main()

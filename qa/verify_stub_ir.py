# -*- coding: utf-8 -*-
"""
Ticket 01c 验收脚本：断言 15 个 stub_ir/q{n}.json 齐全 + 每条可反序列化为合法 QueryIr。
校验 metricId/field 落在 Ontology 白名单（11 指标 / 20 唯一字段），语义合法。
用法：python verify_stub_ir.py
"""
import json
import os
import sys

BASE = os.path.abspath(os.path.join(
    os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'stub_ir'))

# metric_def 白名单（11 指标）
METRIC_IDS = {
    'gmv_total', 'gmv_channel', 'gmv_region', 'sale_amount_product',
    'order_cnt_total', 'order_cnt_channel', 'order_cnt_region',
    'paid_order_cnt', 'paid_user_cnt', 'pay_rate', 'pay_conversion_rate',
}

# field_metadata 白名单（28 行去重后 20 个唯一字段名）
FIELD_NAMES = {
    'dt', 'total_order_cnt', 'paid_order_cnt', 'total_gmv', 'paid_user_cnt',
    'channel', 'channel_name', 'visit_uv', 'order_cnt', 'gmv',
    'order_conversion_rate', 'pay_conversion_rate',
    'province', 'province_name', 'region',
    'product_id', 'product_name', 'category_name', 'sale_cnt', 'sale_amount',
}

VALID_OPS = {'EQ', 'IN', 'LIKE', 'GT', 'GE', 'LT', 'LE'}


def check_ir(ir):
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
        assert m.get('metricId') in METRIC_IDS, '非法 metricId: %r' % m.get('metricId')
    for d in dimensions:
        assert d.get('field') in FIELD_NAMES, '非法维度 field: %r' % d.get('field')
    for f in filters:
        assert f.get('field') in FIELD_NAMES, '非法过滤 field: %r' % f.get('field')
        assert f.get('op') in VALID_OPS, '非法 op: %r' % f.get('op')
        assert 'value' in f, 'filter 缺 value'
    for o in order_by:
        fld = o.get('field')
        assert fld in FIELD_NAMES or fld in METRIC_IDS, '非法排序字段: %r' % fld
        assert o.get('direction') in ('asc', 'desc'), '非法排序方向: %r' % o.get('direction')
    limit = ir.get('limit')
    if limit is not None:
        assert isinstance(limit, int) and limit > 0, 'limit 必须正整数'


def main():
    if not os.path.isdir(BASE):
        print('FAIL: stub_ir 目录不存在:', BASE)
        sys.exit(1)

    files = sorted(
        (f for f in os.listdir(BASE) if f.startswith('q') and f.endswith('.json')),
        key=lambda x: int(x[1:-5]))
    expected = ['q%d.json' % i for i in range(1, 16)]
    actual = [os.path.basename(f) for f in files]
    missing = [e for e in expected if e not in actual]
    assert not missing, '缺失文件: %s' % missing
    print('文件齐全：%d 个' % len(expected))

    for name in files:
        with open(os.path.join(BASE, name), encoding='utf-8') as fp:
            data = json.load(fp)
        assert 'question' in data and data['question'].strip(), '%s 缺 question' % name
        assert 'ir' in data, '%s 缺 ir' % name
        check_ir(data['ir'])
        print('  OK %-8s %s' % (name, data['question']))

    print('全部通过：%d/%d 条可反序列化且字段合法' % (len(files), len(expected)))


if __name__ == '__main__':
    main()

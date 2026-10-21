# -*- coding: utf-8 -*-
"""
Ticket 02 端到端冒烟：五道闸门拦截/放行 + PUT 新指标事件刷新。
用法：python e2e_gate.py [host]  默认 http://127.0.0.1:18081
"""
import json
import sys
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18081'


def ask(q):
    req = urllib.request.Request(HOST + '/api/ask', data=json.dumps({'question': q}).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode('utf-8'))


def put_metric(m):
    req = urllib.request.Request(HOST + '/api/metrics', data=json.dumps(m).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='PUT')
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode('utf-8'))


def main():
    print('== 1. Q1 正常通过五闸门 ==')
    r = ask('10月19日当天的总GMV是多少')
    print('  rows=%s gates=%s answer=%s' % (r.get('rows'), r.get('gates'), r.get('answer')))

    print('== 2. Q3 含 dt+channel_name 过滤，不因维度越界误拦 ==')
    r = ask('10月19日小程序渠道的GMV是多少')
    print('  rows=%s gates=%s answer=%s' % (r.get('rows'), r.get('gates'), r.get('answer')))

    print('== 3. 幻觉指标 → 存在性闸门拦截 ==')
    r = ask('幻觉指标测试专用问法甲')
    print('  answer=%s gates=%s' % (r.get('answer'), r.get('gates')))

    print('== 4. 幻觉字段 → 存在性闸门拦截 ==')
    r = ask('幻觉字段测试专用问法乙')
    print('  answer=%s gates=%s' % (r.get('answer'), r.get('gates')))

    print('== 5. PUT 新指标前，adv_gate_test 应被存在性闸门拦 ==')
    r = ask('新指标刷新测试专用问法丙')
    print('  answer=%s gates=%s' % (r.get('answer'), r.get('gates')))

    print('== 6. PUT 新指标 adv_gate_test ==')
    m = put_metric({'metricId': 'adv_gate_test', 'metricName': '闸门测试指标',
                    'metricSql': 'SUM(total_gmv)', 'tableName': 'ads_trade_overview_di',
                    'metricType': 'simple', 'dimensions': 'dt'})
    print('  PUT 返回 metricId=%s id=%s' % (m.get('metricId'), m.get('id')))

    print('== 7. PUT 后立即重问，事件刷新应识别新指标 ==')
    r = ask('新指标刷新测试专用问法丙')
    print('  rows=%s gates=%s answer=%s' % (r.get('rows'), r.get('gates'), r.get('answer')))


if __name__ == '__main__':
    main()

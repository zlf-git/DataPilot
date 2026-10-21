# -*- coding: utf-8 -*-
"""
Ticket 01c 端到端冒烟：用 15 道评测集原题（带问号）打 POST /api/ask，
验证 StubLlmClient 归一化匹配能全部命中（ir 的 metrics 或 dimensions 非空）。
另加归一化变体（号→日 / 多空格 / 尾标点 / 全角数字）验证模糊匹配。
用法：python e2e_stub.py [host]  默认 http://127.0.0.1:18080
"""
import json
import sys
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'
URL = HOST + '/api/ask'

# 15 道评测集原题（含末尾问号，与 docs/评测集.md 一致）
QUESTIONS = [
    '10月19日当天的总GMV是多少？',
    '近7天累计支付订单数是多少？',
    '10月19日小程序渠道的GMV是多少？',
    '华东地区有哪些省份？',
    '电脑外设类目下有哪些商品？',
    '10月19日各渠道的支付转化率分别是多少？按转化率从高到低排序。',
    '10月19日GMV最高的省份是哪个？',
    '10月19日销量排名前三的商品是哪些？',
    '10月19日支付订单数占总订单数的比例（支付率）是多少？',
    '10月19日华北地区的订单量是多少？',
    '近7天GMV最高的渠道是哪个？',
    '近7天GMV最高的大区是哪个？',
    '近7天销售额最高的商品类目是哪个？金额多少？',
    '近7天里总GMV最高的是哪一天？',
    '10月19日各渠道GMV占当天总GMV的比例分别是多少？',
]


def ask(question):
    body = json.dumps({'question': question}).encode('utf-8')
    req = urllib.request.Request(URL, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode('utf-8'))


def hit(ir):
    if not ir:
        return False
    return bool((ir.get('metrics') or []) or (ir.get('dimensions') or []))


def main():
    print('== 15 道评测集原题逐字命中 ==')
    ok = 0
    for i, q in enumerate(QUESTIONS, 1):
        r = ask(q)
        matched = hit(r.get('ir'))
        tag = 'OK ' if matched else 'FAIL'
        if matched:
            ok += 1
        ir = r.get('ir') or {}
        metrics = [m.get('metricId') for m in (ir.get('metrics') or [])]
        dims = [d.get('field') for d in (ir.get('dimensions') or [])]
        print('  %s Q%-2d metrics=%s dims=%s' % (tag, i, metrics, dims))
    print('命中 %d/15' % ok)

    print('\n== 归一化变体（Q1 格式扰动：号→日 / 空格 / 标点 / 全角，语义不变）==')
    variants = [
        ('10月19号当天的总GMV是多少', '号→日'),
        ('10月19日  当天的  总GMV   是多少', '多空格'),
        ('10月19日当天的总GMV是多少？', '尾问号'),
        ('１０月１９日当天的总ＧＭＶ是多少', '全角数字字母'),
        ('10月19日当天的总GMV是多少。', '尾句号'),
    ]
    vok = 0
    for q, desc in variants:
        try:
            r = ask(q)
            m = hit(r.get('ir'))
            if m:
                vok += 1
            print('  %s %-24s %s' % ('OK ' if m else 'FAIL', desc, q))
        except Exception as e:
            print('  ERR %-24s %s -> %s' % (desc, q, e))
    print('变体命中 %d/%d' % (vok, len(variants)))

    # 未命中兜底：无关问题应返回「无法识别」且 ir 空
    print('\n== 未命中兜底 ==')
    try:
        r = ask('今天天气怎么样')
        print('  ir metrics=%s dims=%s, answer=%s' % (
            (r.get('ir') or {}).get('metrics'),
            (r.get('ir') or {}).get('dimensions'),
            r.get('answer')))
    except Exception as e:
        print('  ERR', e)

    sys.exit(0 if ok == 15 else 1)


if __name__ == '__main__':
    main()

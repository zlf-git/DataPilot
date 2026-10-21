# -*- coding: utf-8 -*-
"""
Ticket 11 端到端验收：解释（answer/chartType）+ 报告（reportHtml）。

判据（审计 P1#9 收紧后）：不是「非空 html 就算过」，而是
  1. answer 非空
  2. chartType 按结果形状判定正确（单值 metric / 多行单列 bar / 两列非日期 bar / 日期首列 line / 3+ 列 table）
  3. reportHtml 含 echarts
  4. **series 长度 = rows 长度**（从页面里抠出 option JSON 解析后再数，不是 contains("series")）
  5. series 名 = 各行首列值，且顺序一致（报告与结果数据对齐）
  6. 报告里带 traceId（能和 agent_run / agent_step 对上）

覆盖形状：Q1 单值（metric）、Q4 单列多行（bar）、Q6/Q8/Q13 两列（bar）。
line（日期首列）与 table（3+ 列）在当前 15 题里没有对应用例，由单测
`ExplainStepTest`/`ReportStepTest` 覆盖 —— 这里不为了凑形状去编一条评测集没有的查询。

用法：python verify_explain.py [host]     默认 http://127.0.0.1:18080
退出码：0 通过 / 1 断言失败 / 2 服务不可达
"""
import json
import re
import sys
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'

# 本机 HTTP_PROXY 会把「连接被拒」伪装成 502，绕开代理直连
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# (问题, 期望图表类型)；问题取自 stub_ir 里现有的 15 题
CASES = [
    ('10月19日当天的总GMV是多少', 'metric'),
    ('华东地区有哪些省份', 'bar'),
    ('10月19日各渠道的支付转化率分别是多少？按转化率从高到低排序', 'bar'),
    ('10月19日销量排名前三的商品是哪些', 'bar'),
    ('近7天销售额最高的商品类目是哪个？金额多少', 'bar'),
]

fails = []
checks = []


def ask(question):
    req = urllib.request.Request(HOST + '/api/ask',
                                data=json.dumps({'question': question}).encode('utf-8'),
                                headers={'Content-Type': 'application/json'})
    with _opener.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode('utf-8'))


def check(name, ok, detail=''):
    checks.append((name, ok, detail))
    if not ok:
        fails.append('%s：%s' % (name, detail))
    print('  [%s] %-40s %s' % ('PASS' if ok else 'FAIL', name, detail))


def option_of(html):
    """从 reportHtml 里抠出 `var option = {...};` 并解析"""
    m = re.search(r'var option = (\{.*?\});\n', html, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(1))
    except json.JSONDecodeError:
        return None


def main():
    for question, expect_type in CASES:
        r = ask(question)
        rows = r.get('rows') or []
        print('== %s（%d 行 × %d 列）==' % (question, len(rows), len(rows[0]) if rows else 0))

        # 成功时 answer 也有内容（Ticket 11），所以判据是显式 status —— 这也是本票对
        # AskResponse 的增量字段，回答「这次到底跑成功没有」
        check('status = SUCCESS', r.get('status') == 'SUCCESS', str(r.get('status')))
        check('answer 非空', bool((r.get('answer') or '').strip()), (r.get('answer') or '')[:40])
        check('chartType = %s' % expect_type, r.get('chartType') == expect_type, str(r.get('chartType')))

        html = r.get('reportHtml') or ''
        check('reportHtml 含 echarts', 'echarts' in html, '%d 字符' % len(html))

        option = option_of(html)
        check('option JSON 可解析', option is not None, '')
        if option is None:
            continue
        series = option.get('series') or []
        check('series 长度 = rows 长度（%d）' % len(rows), len(series) == len(rows),
              'series=%d rows=%d' % (len(series), len(rows)))

        # 报告与结果对齐：series 名 = 各行首列值，顺序一致
        if rows:
            first_col = list(rows[0].keys())[0]
            expected_names = [str(row.get(first_col)) for row in rows]
            actual_names = [s.get('name') for s in series]
            check('series 名与首列值逐行对齐', actual_names == expected_names,
                  '%s vs %s' % (actual_names[:3], expected_names[:3]))

        check('报告带 traceId', (r.get('traceId') or '') in html, r.get('traceId'))

    print()
    if fails:
        print('FAIL %d/%d：' % (len(fails), len(checks)))
        for f in fails:
            print('  - ' + f)
        return 1
    print('PASS：%d/%d 项全绿' % (len(checks), len(checks)))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except urllib.error.URLError as e:
        print('服务不可达 %s：%s' % (HOST, e))
        sys.exit(2)

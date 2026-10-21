# -*- coding: utf-8 -*-
"""
Ticket 01c/03 端到端验收（诚实版）。

判据（对齐 qa/eval_expectations.json）：
  链路可用   := sql 非空 且 answer 为空（真的执行了且没报错）
  SQL 形状   := SQL 里出现该题 sqlMust 列出的结构片段（DISTINCT / GROUP BY / ORDER BY xx DESC / LIMIT）
  答对       := 链路可用 + 形状达标 + 结果行与期望集合一致
  显式拒绝   := sql 为空 且 answer 含该题 blocker 字样（未实现的题必须走这条）
  静默错     := 期望 BLOCKED 却执行出数，或 SQL 缺 sqlMust 结构 —— 看着成功实则错，必须为 0

用法：python e2e_stub.py [host]   默认 http://127.0.0.1:18080
退出码：0 = 无静默错/无引擎缺陷/无回归/无未归因不符；1 = 否则
"""
import json
import os
import re
import sys
import urllib.request
from datetime import datetime

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'
URL = HOST + '/api/ask'
EXPECT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'eval_expectations.json')

TOL = 0.011


def ask(question):
    body = json.dumps({'question': question}).encode('utf-8')
    req = urllib.request.Request(URL, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode('utf-8'))


def executed(r):
    return bool((r.get('sql') or '').strip()) and not (r.get('answer') or '').strip()


def norm_cell(v):
    """日期列可能被序列化成 epoch 毫秒，统一成 yyyy-MM-dd 再比"""
    if isinstance(v, (int, float)) and not isinstance(v, bool) and 1e11 < abs(float(v)) < 1e13:
        return datetime.fromtimestamp(float(v) / 1000).strftime('%Y-%m-%d')
    return v


def row_match(actual, expect):
    for k, want in expect.items():
        if k not in actual:
            return False
        got = norm_cell(actual[k])
        if isinstance(want, (int, float)) and not isinstance(want, bool):
            try:
                if abs(float(got) - float(want)) > TOL:
                    return False
            except (TypeError, ValueError):
                return False
        else:
            if str(got) != str(want):
                return False
    return True


def compare_rows(rows, expected):
    if len(rows) != len(expected):
        return False, '行数 %d ≠ 期望 %d（实际 %s）' % (len(rows), len(expected), rows[:4])
    remaining = list(rows)
    for e in expected:
        hit = next((r for r in remaining if row_match(r, e)), None)
        if hit is None:
            return False, '缺期望行 %s（实际 %s）' % (e, rows[:4])
        remaining.remove(hit)
    return True, ''


def missing_sql(sql, must):
    upper = (sql or '').upper()
    return [m for m in must or [] if m.upper() not in upper]


def main():
    with open(EXPECT_FILE, encoding='utf-8') as fp:
        expect = json.load(fp)['items']

    print('== 15 题逐题判定（执行 + SQL 形状 + 值对拍 + 归因）==')
    print('  %-3s %-6s %-16s %-12s %s' % ('#', 'IR', '判定', '归因', '说明'))
    st = {'ok': 0, 'reject': 0, 'silent': 0, 'bug': 0, 'mismatch': 0,
          'bad': 0, 'ir': 0, 'exec': 0, 'shape': 0}
    caliber_pending = []

    for it in expect:
        no, q, status = it['no'], it['question'], it['status']
        r = ask(q)
        ir = r.get('ir') or {}
        sql = (r.get('sql') or '').strip()
        answer = (r.get('answer') or '').strip()
        rows = r.get('rows') or []

        ir_hit = bool((ir.get('metrics') or []) or (ir.get('dimensions') or []))
        ran = executed(r)
        st['ir'] += 1 if ir_hit else 0
        st['exec'] += 1 if ran else 0

        verdict, blame, note = '', '', ''
        lack = missing_sql(sql, it.get('sqlMust')) if ran else []

        if ran:
            if status == 'BLOCKED':
                verdict, blame = 'SILENT_WRONG', it['blocker']
                note = '本应显式拒绝，却执行了：' + sql[:70]
                st['silent'] += 1
            elif lack:
                verdict, blame = 'ENGINE_BUG', 'Ticket 03'
                note = 'SQL 缺结构 %s：%s' % (','.join(lack), sql[:70])
                st['bug'] += 1
            else:
                st['shape'] += 1
                ok, why = compare_rows(rows, it.get('rows') or [])
                if ok:
                    verdict, st['ok'] = 'OK', st['ok'] + 1
                    note = '%d 行 %s' % (len(rows), rows[0] if rows else '')
                elif it.get('blame'):
                    verdict, blame = 'VALUE_MISMATCH', it['blame']
                    note = why
                    st['mismatch'] += 1
                else:
                    verdict, blame = 'VALUE_MISMATCH', '口径/评测集'
                    note = why
                    st['bad'] += 1
        else:
            if status == 'OK':
                verdict, blame = 'REGRESSION', '链路'
                note = '应可用却未执行：answer=%s' % answer
                st['bad'] += 1
            elif answer and it['blocker'] in answer:
                verdict, blame = 'EXPLICIT_REJECT', it['blocker']
                note = answer
                st['reject'] += 1
            elif answer:
                verdict, blame = 'REJECT_MISATTRIBUTED', '期望 %s' % it['blocker']
                note = answer
                st['bad'] += 1
            else:
                verdict, blame = 'REJECT_UNATTRIBUTED', '-'
                note = 'sql 为空且无 answer'
                st['bad'] += 1

        if ran and it.get('caliberPending'):
            caliber_pending.append('Q%-2d %s' % (no, it['caliberPending']))

        print('  %-3d %-6s %-16s %-12s %s' % (
            no, 'HIT' if ir_hit else 'MISS', verdict, blame, note[:88]))

    total = len(expect)
    print('\n== 汇总 ==')
    print('  IR 命中                    %2d/%d  （只证明桩匹配对）' % (st['ir'], total))
    print('  链路可用（执行出数）        %2d/%d' % (st['exec'], total))
    print('  SQL 形状达标                 %2d/%d  ← Ticket 03 引擎验收' % (st['shape'], total))
    print('  答对（行级对拍）             %2d/%d' % (st['ok'], total))
    print('  值不符但已归因（待 04/05）    %2d/%d' % (st['mismatch'], total))
    print('  显式拒绝（归因正确）          %2d/%d' % (st['reject'], total))
    print('  静默错（看着成功实则错）      %2d/%d  ← 必须为 0' % (st['silent'], total))
    print('  引擎缺陷（SQL 缺结构）        %2d/%d  ← 必须为 0' % (st['bug'], total))
    print('  回归/无归因异常              %2d/%d  ← 必须为 0' % (st['bad'], total))

    if caliber_pending:
        print('\n== 口径/评测集待办（值能算但口径未定，不等于已闭环）==')
        for c in caliber_pending:
            print('  - ' + c)

    also = [(it['no'], it['alsoNeeds']) for it in expect if it.get('alsoNeeds')]
    if also:
        print('\n== 后续票依赖 ==')
        for no, txt in also:
            print('  - Q%-2d %s' % (no, txt))

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
            ok = executed(r) and compare_rows(r.get('rows') or [], [{'gmv_total': 53042.41}])[0]
            vok += 1 if ok else 0
            print('  %s %-14s %s' % ('OK  ' if ok else 'FAIL', desc, q))
        except Exception as e:
            print('  ERR %-14s %s -> %s' % (desc, q, e))
    print('  变体可用 %d/%d（判据同样要求「执行 + 值对」）' % (vok, len(variants)))

    print('\n== 未命中兜底 ==')
    r = ask('今天天气怎么样')
    ir = r.get('ir') or {}
    ok_fb = (not (ir.get('metrics') or ir.get('dimensions'))) and not (r.get('sql') or '').strip() \
        and (r.get('answer') or '').strip()
    print('  %s ir=%s/%s sql=%r answer=%r' % (
        'OK  ' if ok_fb else 'FAIL',
        ir.get('metrics'), ir.get('dimensions'),
        (r.get('sql') or '')[:20], r.get('answer')))

    ok_final = (st['silent'] == 0 and st['bug'] == 0 and st['bad'] == 0
                and vok == len(variants) and ok_fb)
    print('\nCONCLUSION: %s（可用 %d/15，答对 %d，静默错 %d，显式拒绝 %d，口径待定 %d）' % (
        'PASS' if ok_final else 'FAIL', st['exec'], st['ok'], st['silent'], st['reject'], st['mismatch']))
    sys.exit(0 if ok_final else 1)


if __name__ == '__main__':
    main()

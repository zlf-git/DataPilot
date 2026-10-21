# -*- coding: utf-8 -*-
"""
Ticket 01c 端到端验收（诚实版）。

上一版判据是「IR 里 metrics 或 dimensions 非空」，把 10 道算不出结果的题也判成 OK，
导致「15/15 通过」≠「15 题能答」。本版判据对齐真实行为：

  链路可用  := sql 非空 且 answer 为空（真的执行了且没报错）
  答对      := 链路可用 且 结果值 == 期望表里的值
  显式拒绝  := sql 为空 且 answer 说明了原因（本票未实现的题必须走这条，不许静默出数）
  静默错    := 链路可用 但期望是 BLOCKED，或 SQL 缺 GROUP BY/LIMIT/DISTINCT 等必需能力
               —— 这是最危险的一类：看着成功，数值全错

期望表见 qa/eval_expectations.json（06b 的 run_eval 复用同一份，避免判据漂移）。

用法：python e2e_stub.py [host]   默认 http://127.0.0.1:18080
退出码：0 = 无静默错、无预期不符；1 = 有静默错或预期不符
"""
import json
import os
import sys
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'
URL = HOST + '/api/ask'
EXPECT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'eval_expectations.json')

TOL = 0.011  # 金额/比率比较容差


def ask(question):
    body = json.dumps({'question': question}).encode('utf-8')
    req = urllib.request.Request(URL, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode('utf-8'))


def executed(r):
    """真的走完链路：有 SQL、有结果集、没被兜底文案打断"""
    sql = (r.get('sql') or '').strip()
    answer = (r.get('answer') or '').strip()
    return bool(sql) and not answer


def match_values(rows, expected):
    """按期望表逐指标比对第一行数值"""
    if not rows:
        return False, 'rows 为空'
    row = rows[0]
    for key, want in expected.items():
        got = row.get(key)
        if got is None:
            return False, '缺列 %s（实际列 %s）' % (key, list(row.keys()))
        try:
            if abs(float(got) - float(want)) > TOL:
                return False, '%s=%s，期望 %s' % (key, got, want)
        except (TypeError, ValueError):
            return False, '%s=%r 非数值' % (key, got)
    return True, ''


def missing_capability(sql, requires):
    """SQL 是否缺了 IR 要求的语义（静默降级检测）"""
    upper = (sql or '').upper()
    lack = []
    for cap in requires or []:
        if cap == 'GROUP BY' and 'GROUP BY' not in upper:
            lack.append(cap)
        elif cap == 'ORDER BY' and 'ORDER BY' not in upper:
            lack.append(cap)
        elif cap == 'LIMIT' and 'LIMIT' not in upper:
            lack.append(cap)
        elif cap == 'DISTINCT' and 'DISTINCT' not in upper:
            lack.append(cap)
        elif cap == 'RATIO' and ('ROUND' not in upper and '/' not in (sql or '')):
            lack.append(cap)
    return lack


def main():
    with open(EXPECT_FILE, encoding='utf-8') as fp:
        expect = json.load(fp)['items']

    print('== 15 题逐题判定（链路可用 + 值对拍 + 归因）==')
    print('  %-3s %-6s %-14s %-20s %s' % ('#', 'IR', '判定', '归因', '说明'))
    stat = {'ok': 0, 'reject': 0, 'silent': 0, 'bad': 0, 'ir': 0, 'exec': 0}
    caliber_pending = []

    for it in expect:
        no, q = it['no'], it['question']
        status = it['status']
        r = ask(q)
        ir = r.get('ir') or {}
        sql = (r.get('sql') or '').strip()
        answer = (r.get('answer') or '').strip()
        rows = r.get('rows') or []

        ir_hit = bool((ir.get('metrics') or []) or (ir.get('dimensions') or []))
        ran = executed(r)
        stat['ir'] += 1 if ir_hit else 0
        stat['exec'] += 1 if ran else 0

        verdict, blame, note = '', '', ''

        if ran:
            lack = missing_capability(sql, it.get('requires'))
            if status == 'BLOCKED':
                verdict, blame = 'SILENT_WRONG', it['blocker']
                note = '本应显式拒绝，却返回了结果（缺 %s）' % (','.join(lack) or '未实现语义')
                stat['silent'] += 1
            elif lack:
                verdict, blame = 'SILENT_WRONG', 'Ticket 03'
                note = 'SQL 缺 %s，属于静默降级' % ','.join(lack)
                stat['silent'] += 1
            else:
                ok, why = match_values(rows, it.get('values') or {})
                if ok:
                    verdict, stat_key = 'OK', 'ok'
                    note = str(rows[0])
                else:
                    verdict, blame, note = 'VALUE_MISMATCH', '口径/模板', why
                    stat_key = 'bad'
                stat[stat_key] += 1
        else:
            if status == 'OK':
                verdict, blame = 'REGRESSION', '链路'
                note = '应可用却未执行：answer=%s' % answer
                stat['bad'] += 1
            else:
                # 归因必须可验证：拒绝文案里要出现期望表标的 blocker（Ticket 03/04/05），
                # 否则「显式拒绝」只是拒绝，没法证明失败原因找对了。
                if answer and it['blocker'] in answer:
                    verdict, blame = 'EXPLICIT_REJECT', it['blocker']
                    note = answer
                    stat['reject'] += 1
                elif answer and ('未实现' in answer or '不支持' in answer or '不存在' in answer):
                    verdict, blame = 'REJECT_MISATTRIBUTED', '期望 %s' % it['blocker']
                    note = answer
                    stat['bad'] += 1
                else:
                    verdict, blame = 'REJECT_UNATTRIBUTED', '-'
                    note = 'answer=%r 未说明归因' % answer
                    stat['bad'] += 1

        if ran and it.get('caliberPending'):
            caliber_pending.append('Q%d %s' % (no, it['caliberPending']))

        print('  %-3d %-6s %-14s %-20s %s' % (
            no, 'HIT' if ir_hit else 'MISS', verdict, blame, note[:90]))

    total = len(expect)
    print('\n== 汇总 ==')
    print('  IR 命中                  %2d/%d  （只证明桩匹配对，不代表能答）' % (stat['ir'], total))
    print('  链路可用（执行出数）      %2d/%d  ← 真实能力' % (stat['exec'], total))
    print('  答对（值对拍通过）        %2d/%d' % (stat['ok'], total))
    print('  显式拒绝（归因正确）      %2d/%d  （未实现语义必须走这条）' % (stat['reject'], total))
    print('  静默错（看着成功实则错）  %2d/%d  ← 必须为 0' % (stat['silent'], total))
    print('  预期不符（回归/无归因）   %2d/%d  ← 必须为 0' % (stat['bad'], total))

    if caliber_pending:
        print('\n== 口径待办（值现在对，口径未固化，Ticket 04 回填 timeRange）==')
        for c in caliber_pending:
            print('  - ' + c)

    also = [(it['no'], it['alsoNeeds']) for it in expect if it.get('alsoNeeds')]
    if also:
        print('\n== 后续票依赖（本票先阻塞，别当已解决）==')
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
            ok = executed(r) and match_values(r.get('rows') or [], {'gmv_total': 53042.41})[0]
            vok += 1 if ok else 0
            print('  %s %-14s %s' % ('OK  ' if ok else 'FAIL', desc, q))
        except Exception as e:
            print('  ERR %-14s %s -> %s' % (desc, q, e))
    print('  变体可用 %d/%d（判据同样要求「执行 + 值对」，不是「IR 命中」）' % (vok, len(variants)))

    print('\n== 未命中兜底 ==')
    r = ask('今天天气怎么样')
    ir = r.get('ir') or {}
    ok = (not (ir.get('metrics') or ir.get('dimensions'))) and not (r.get('sql') or '').strip() \
        and (r.get('answer') or '').strip()
    print('  %s ir=%s/%s sql=%r answer=%r' % (
        'OK  ' if ok else 'FAIL',
        ir.get('metrics'), ir.get('dimensions'),
        (r.get('sql') or '')[:20], r.get('answer')))

    print()
    ok_final = stat['silent'] == 0 and stat['bad'] == 0 and vok == len(variants) and ok
    print('CONCLUSION: %s（可用 %d/15，静默错 %d，显式拒绝 %d）' % (
        'PASS' if ok_final else 'FAIL', stat['exec'], stat['silent'], stat['reject']))
    sys.exit(0 if ok_final else 1)


if __name__ == '__main__':
    main()

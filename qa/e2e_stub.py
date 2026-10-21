# -*- coding: utf-8 -*-
"""
Ticket 01c/03 端到端验收（诚实版）。

判据（对齐 qa/eval_expectations.json）：
  链路可用   := sql 非空 且 status=SUCCESS（Ticket 11 起 answer 成功时也有内容，
                判据从「answer 为空」改成显式 status；无 status 的老响应仍按旧口径）
  SQL 形状   := SQL 里出现该题 sqlMust 列出的结构片段（DISTINCT / GROUP BY / ORDER BY xx DESC / LIMIT）
  时间窗口   := window 声明的半开区间（dt &gt; from 且 dt &lt;= anchor、禁 BETWEEN、IR 回填 anchor == 数据 MAX(dt)）
                ★ 硬规则（QA-04 P1-2）：窗口题**必须**断言 SQL 形状，值对拍不足以判别半开/闭区间
                  （7 天窗口的 2026-10-12 是空分区，BETWEEN 与半开逐题等值）；
                  另用 days=3 探针做值级判别：157702.85（半开 3 天）vs 223887.35（闭区间 4 天）
  答对       := 链路可用 + 形状达标 + 结果行与期望集合一致
  显式拒绝   := sql 为空 且 answer 含该题 blocker 字样（未实现的题必须走这条）
  静默错     := 期望 BLOCKED 却执行出数，或 SQL 缺 sqlMust 结构 —— 看着成功实则错，必须为 0

用法：python e2e_stub.py [host]   默认 http://127.0.0.1:18080
退出码：0 = 无静默错/无引擎缺陷/无回归/无未归因不符；1 = 否则
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
from datetime import datetime, timedelta

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'
URL = HOST + '/api/ask'
HERE = os.path.dirname(os.path.abspath(__file__))
EXPECT_FILE = os.path.join(HERE, 'eval_expectations.json')
REPO = os.path.dirname(HERE)

TOL = 0.011


def expectation_version():
    """期望表版本（QA-03 环境事故的教训）：期望表会被后续票改动，用 HEAD 版本评旧 commit 会误报静默错。
    这里把「被测代码 commit + 期望表内容指纹」都打出来，报告可核对。"""
    def git(*args):
        try:
            return subprocess.run(['git', '-C', REPO] + list(args), capture_output=True,
                                  text=True, timeout=10).stdout.strip()
        except Exception as e:  # noqa: BLE001
            return '<%s>' % e
    head = git('log', '-1', '--oneline')
    dirty = len([l for l in git('status', '--porcelain').splitlines() if l.strip()])
    import hashlib
    with open(EXPECT_FILE, 'rb') as fp:
        raw = fp.read()
    digest = hashlib.sha256(raw).hexdigest()[:12]
    return '期望表 %s（sha256:%s，%d 字节） | 被测代码 %s%s' % (
        os.path.basename(EXPECT_FILE), digest, len(raw), head or '<非 git 环境>',
        '（工作区有 %d 处未提交改动）' % dirty if dirty else '')


def ask(question):
    body = json.dumps({'question': question}).encode('utf-8')
    req = urllib.request.Request(URL, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode('utf-8'))


def executed(r):
    """真的执行出数：Ticket 11 起 answer 在成功时也有内容，判据改用显式 status 字段。
    兼容老响应（无 status）：仍按「sql 非空且 answer 为空」判。"""
    if not (r.get('sql') or '').strip():
        return False
    status = (r.get('status') or '').strip()
    if status:
        return status == 'SUCCESS'
    return not (r.get('answer') or '').strip()


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


def window_check(sql, params, ir, win):
    """Ticket 04 时间窗口断言：半开区间 dt > from AND dt <= anchor，禁 BETWEEN，anchor 由 MAX(dt) 回填"""
    issues = []
    if 'BETWEEN' in (sql or '').upper():
        issues.append('SQL 出现 BETWEEN（闭区间会多取一天）')
    ps = [str(x) for x in (params or [])]
    for label, want in (('下界', win['from']), ('锚点', win['anchor'])):
        if want not in ps:
            issues.append('参数缺%s %s（实际 %s）' % (label, want, ps))
    got_anchor = ((ir or {}).get('timeRange') or {}).get('anchor')
    if got_anchor != win['anchor']:
        issues.append('IR 回填 anchor=%r ≠ 数据最新日 %s' % (got_anchor, win['anchor']))
    return issues


def main():
    with open(EXPECT_FILE, encoding='utf-8') as fp:
        expect = json.load(fp)['items']

    print('== 版本 ==')
    print('  ' + expectation_version())
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
        if ran and it.get('window'):
            lack += window_check(sql, r.get('params'), ir, it['window'])

        if ran:
            if status == 'BLOCKED':
                verdict, blame = 'SILENT_WRONG', it['blocker']
                note = '本应显式拒绝，却执行了：' + sql[:70]
                st['silent'] += 1
            elif lack:
                verdict, blame = 'ENGINE_BUG', 'Ticket 03/04'
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

    print('\n== 反例回归（QA-03 P1-1/P1-2/P2-1：必须显式拒绝，不能执行出数）==')
    neg_cases = [
        ('空白-空串', '', '问题为空'),
        ('空白-空格', ' ', '问题为空'),
        ('空白-换行', '\n', '问题为空'),
        ('未命中', '今天天气怎么样', '无法识别'),
        ('双意向拼接', '10月19日当天的总GMV是多少？另外华东地区有哪些省份', '无法识别'),
        ('跨表维度', '反例：按渠道名称看总GMV', '不属于表'),
    ]
    neg_fail = 0
    for desc, q, want in neg_cases:
        try:
            r = ask(q)
            sql = (r.get('sql') or '').strip()
            answer = (r.get('answer') or '').strip()
            ok = (not sql) and bool(answer) and (want in answer)
            neg_fail += 0 if ok else 1
            print('  %s %-12s sql=%r answer=%r' % (
                'OK  ' if ok else 'FAIL', desc, sql[:26], answer[:60]))
        except Exception as e:  # noqa: BLE001
            neg_fail += 1
            print('  FAIL %-12s 异常 %s' % (desc, e))

    print('\n== 行序稳定性（同一问题连跑两轮，行序必须一致）==')
    stable_fail = 0
    for desc, q in [('Q4 DETAIL', '华东地区有哪些省份？'),
                    ('Q5 DETAIL', '电脑外设类目下有哪些商品？'),
                    ('Q6 并列排序', '10月19日各渠道的支付转化率分别是多少？按转化率从高到低排序。')]:
        try:
            a = [json.dumps(x, ensure_ascii=False, sort_keys=True) for x in (ask(q).get('rows') or [])]
            b = [json.dumps(x, ensure_ascii=False, sort_keys=True) for x in (ask(q).get('rows') or [])]
            ok = a == b and bool(a)
            stable_fail += 0 if ok else 1
            print('  %s %-12s %s' % ('OK  ' if ok else 'FAIL', desc, a[:4]))
        except Exception as e:  # noqa: BLE001
            stable_fail += 1
            print('  FAIL %-12s 异常 %s' % (desc, e))

    # QA-04 P1-2：7 天窗口下 2026-10-12 是空分区，BETWEEN(闭区间/8 天) 与半开(7 天) 逐题返回值完全一样，
    # 「不取 8 天」只对 SQL 形状敏感、对值不敏感。这里用 days=3 做值级探针：
    # 锚点 2026-10-19，半开 dt > '10-16' 取 10-17/18/19 = 157702.85；BETWEEN '10-16'..'10-19' 会多吃 10-16 的 66184.50 = 223887.35。
    # 因此「值等于 157702.85 且不等于 223887.35」才是能与闭区间真正区分开的判据。
    print('\n== 窗口口径探针（days=3：半开 157702.85 vs 闭区间 223887.35）==')
    probe_fail = 0
    try:
        r = ask('近3天累计GMV是多少？')
        sql = r.get('sql') or ''
        params = [str(x) for x in (r.get('params') or [])]
        rows = r.get('rows') or []
        got = rows[0].get('gmv_total') if rows else None
        issues = []
        if 'BETWEEN' in sql.upper():
            issues.append('SQL 出现 BETWEEN（闭区间会多取一天）')
        if 'DT > ?' not in sql.upper() or 'DT <= ?' not in sql.upper():
            issues.append('SQL 不是半开区间 dt > ? AND dt <= ?')
        if not executed(r):
            issues.append('未执行出数（answer=%r）' % ((r.get('answer') or '')[:40],))
        if len(params) != 2:
            issues.append('参数个数 %d ≠ 2（应为 anchor-days / anchor）' % len(params))
        else:
            try:
                anchor_day = datetime.strptime(params[1], '%Y-%m-%d')
                want_from = (anchor_day - timedelta(days=3)).strftime('%Y-%m-%d')
                if params[0] != want_from:
                    issues.append('下界 %s ≠ 锚点 %s - 3 天（%s）' % (params[0], params[1], want_from))
            except ValueError:
                issues.append('锚点参数 %r 不是 yyyy-MM-dd' % (params[1],))
        try:
            val = float(got) if got is not None else None
        except (TypeError, ValueError):
            val = None
        if val is None:
            issues.append('取到的 GMV 不是数值：%r' % (got,))
        else:
            if abs(val - 157702.85) > TOL:
                issues.append('值 %.2f ≠ 157702.85（半开 3 天）' % val)
            if abs(val - 223887.35) <= TOL:
                issues.append('值命中闭区间 223887.35 → 取到 8 天/4 天窗口')
        probe_fail = 0 if not issues else 1
        print('  %s sql=%s' % ('OK  ' if not issues else 'FAIL', sql[:110]))
        print('     params=%s rows=%d gmv_total=%r' % (params, len(rows), got))
        for i in issues:
            print('     - ' + i)
    except Exception as e:  # noqa: BLE001
        probe_fail = 1
        print('  FAIL 探针异常 %s' % e)

    ok_final = (st['silent'] == 0 and st['bug'] == 0 and st['bad'] == 0
                and vok == len(variants) and neg_fail == 0 and stable_fail == 0
                and probe_fail == 0)
    print('\nCONCLUSION: %s（可用 %d/15，答对 %d，静默错 %d，显式拒绝 %d，口径待定 %d，'
          '反例不符 %d，行序不稳 %d，窗口探针不符 %d）' % (
              'PASS' if ok_final else 'FAIL', st['exec'], st['ok'], st['silent'], st['reject'],
              st['mismatch'], neg_fail, stable_fail, probe_fail))
    sys.exit(0 if ok_final else 1)


if __name__ == '__main__':
    main()

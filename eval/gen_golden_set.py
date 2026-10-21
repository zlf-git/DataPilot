# -*- coding: utf-8 -*-
"""
Ticket 06a：从 docs/评测集.md 转出 eval/golden_set.json。

设计要点（对齐票面与审计结论）：
  1. 题目与 expect_sql **解析自 docs/评测集.md 的 ```sql 代码块**，不手抄——文档改了重新跑一遍即可。
     （审计 P0#4：题库以结构化 golden_set.json 为准，但它的来源必须可追溯到文档，否则两处判据会漂移。）
  2. expect_rows 一律**实测**：15 条 expect_sql 各自独立连接、独立 execute，结果原样落盘。
  3. expect_type 三值：scalar / stringset / rowset。先由「结果形状」自动推断，再与本脚本声明的
     票面期望值交叉校验；两者不一致直接报错退出，避免形状漂移后静默改了判定口径。
  4. expect_value 的形状必须与 expect_type 匹配：
        scalar    -> 数字/字符串（首行首列）
        stringset -> 一维数组（首列去重后按 SQL 顺序）
        rowset    -> 对象数组（= expect_rows 全量）
  5. 与 qa/eval_expectations.json 交叉校验（集合语义、容差 0.011）。期望表是 Ticket 03/04/05 人工核对过的
     值，若与实测不符说明数据或口径变了，必须显式报错而不是让 golden_set 悄悄换值。
  6. sql_must / window 从 eval_expectations.json 机械合并（窗口题必须能断言 SQL 形状，QA-04 P1-2），
     并在 sources 里记两份来源的 sha256，报告可成对核对。

用法：
  python eval/gen_golden_set.py            # 重新生成 eval/golden_set.json
  python eval/gen_golden_set.py --dry-run  # 只校验不写盘
退出码：0 = 生成/校验通过；1 = 解析失败 / 实测与期望表不符 / 类型推断与票面声明不符
"""
import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime

import pymysql
from pymysql.cursors import DictCursor

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DOC = os.path.join(REPO, 'docs', '评测集.md')
EXPECT = os.path.join(REPO, 'qa', 'eval_expectations.json')
OUT = os.path.join(HERE, 'golden_set.json')

DB = dict(host='127.0.0.1', port=9030, user='root', password='', charset='utf8mb4',
          cursorclass=DictCursor, connect_timeout=10, read_timeout=30)
SCHEMA = 'mall_dw'
TOL = 0.011

# 票面（06a 任务 2）声明的类型；用于和形状推断交叉校验
DECLARED_TYPE = {
    1: 'scalar', 2: 'scalar', 3: 'scalar', 4: 'stringset', 5: 'stringset',
    6: 'rowset', 7: 'rowset', 8: 'rowset', 9: 'scalar', 10: 'scalar',
    11: 'rowset', 12: 'rowset', 13: 'rowset', 14: 'rowset', 15: 'rowset',
}

DIFFICULTY = {}
for _n in range(1, 6):
    DIFFICULTY[_n] = 'easy'
for _n in range(6, 11):
    DIFFICULTY[_n] = 'medium'
for _n in range(11, 16):
    DIFFICULTY[_n] = 'hard'

Q_BLOCK = re.compile(r'\*\*Q(\d+)\*\*[ \t]*(.+?)[ \t]*\n+```sql\n(.*?)```', re.S)


def sha256_full(path):
    """来源内容指纹（全量，不用 12 位前缀：前缀无法排除碰撞，也与 result.json 的 sha 对不上）。

    <p>按 UTF-8 文本读入、把换行统一成 \\n 再哈希，**不哈希原始字节**。
    仓库里存的是 LF，Windows 工作区 checkout 成 CRLF，同一个提交在两处会算出不同指纹
    （QA-06c P1-1 实测：工作区 6abbf84e… / `git archive` 干净副本 1eb75d3a…，
    结果「开发机 PASS、评审机 FAIL」）。指纹要盯的是文档内容变没变，不是谁用什么换行风格 checkout。
    """
    with open(path, 'r', encoding='utf-8') as fp:
        text = fp.read()
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def strip_sql_comments(sql):
    """去掉 `--` 行注释并压成单行：expect_sql 要能独立执行，注释留着不影响，但落盘以干净语句为准。"""
    lines = []
    for ln in sql.splitlines():
        if ln.strip().startswith('--'):
            continue
        lines.append(ln.strip())
    text = ' '.join(x for x in lines if x)
    return re.sub(r'\s+', ' ', text).strip()


def parse_doc():
    with open(DOC, encoding='utf-8') as fp:
        text = fp.read()
    items = {}
    for m in Q_BLOCK.finditer(text):
        no = int(m.group(1))
        question = re.sub(r'\s+', ' ', m.group(2)).strip()
        sql = strip_sql_comments(m.group(3))
        if no in items:
            raise SystemExit('文档里 Q%d 重复出现，解析有歧义' % no)
        items[no] = dict(no=no, question=question, expect_sql=sql)
    missing = sorted(set(range(1, 16)) - set(items))
    if missing:
        raise SystemExit('docs/评测集.md 缺题：%s' % missing)
    if len(items) != 15:
        raise SystemExit('解析到 %d 题，期望 15 题' % len(items))
    return items


def normalize(v):
    """Decimal -> float、date/datetime -> yyyy-MM-dd、None -> None，让 json 可序列化且可比。"""
    from decimal import Decimal
    import datetime as dtmod
    if isinstance(v, Decimal):
        return float(v)
    if isinstance(v, dtmod.datetime):
        return v.strftime('%Y-%m-%d %H:%M:%S') if (v.hour or v.minute or v.second) else v.strftime('%Y-%m-%d')
    if isinstance(v, dtmod.date):
        return v.strftime('%Y-%m-%d')
    if isinstance(v, (bytes, bytearray)):
        return v.decode('utf-8', 'replace')
    return v


def run_each(items):
    """15 条 expect_sql 各用独立连接、独立 execute —— 票面要求「单独执行一遍」，且一题失败不掩盖其它题。"""
    results, failures = {}, []
    for no in sorted(items):
        sql = items[no]['expect_sql']
        try:
            conn = pymysql.connect(**DB)
            try:
                conn.select_db(SCHEMA)
                with conn.cursor() as cur:
                    cur.execute(sql)
                    cols = [d[0] for d in cur.description] if cur.description else []
                    raw = cur.fetchall()
            finally:
                conn.close()
        except Exception as e:  # noqa: BLE001
            failures.append('Q%d 执行失败：%s | %s' % (no, e, sql[:120]))
            continue
        rows = [{c: normalize(r[c]) for c in cols} for r in raw]
        results[no] = dict(cols=cols, rows=rows)
    return results, failures


def infer_type(rows, cols):
    """形状推断：
         0 行或 0 列        -> 无法判定（报错）
         多行 且 只有 1 列  -> stringset（DISTINCT 列举类）
         恰好 1 行 1 列     -> scalar
         其它（含 1 行多列）-> rowset
    """
    if not rows or not cols:
        return None
    if len(rows) > 1 and len(cols) == 1:
        return 'stringset'
    if len(rows) == 1 and len(cols) == 1:
        return 'scalar'
    return 'rowset'


def build_value(expect_type, res):
    rows, cols = res['rows'], res['cols']
    if expect_type == 'scalar':
        return rows[0][cols[0]]
    if expect_type == 'stringset':
        out = []
        for r in rows:
            v = r[cols[0]]
            if v not in out:
                out.append(v)
        return out
    return rows


def cell_eq(a, b, tol=TOL):
    if isinstance(a, bool) or isinstance(b, bool):
        return a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(a) - float(b)) <= tol
    return str(a) == str(b)


def rowset_eq(actual, expected, tol=TOL):
    """集合语义：行数相同且期望行能一一配对（不看列名、不看行序）。"""
    if len(actual) != len(expected):
        return False, '行数 %d ≠ 期望 %d' % (len(actual), len(expected))
    pool = list(actual)
    for e in expected:
        ev = list(e.values())
        hit = None
        for r in pool:
            rv = list(r.values())
            if len(rv) == len(ev) and all(cell_eq(x, y, tol) for x, y in zip(rv, ev)):
                hit = r
                break
        if hit is None:
            return False, '缺期望行 %s' % (e,)
        pool.remove(hit)
    return True, ''


def cross_check(items, results):
    """与 qa/eval_expectations.json 对拍：期望表是人工核过的值，实测不符必须显式失败。"""
    with open(EXPECT, encoding='utf-8') as fp:
        exp = {it['no']: it for it in json.load(fp)['items']}
    problems = []
    for no in sorted(items):
        if no not in exp:
            problems.append('期望表缺 Q%d' % no)
            continue
        want = exp[no].get('rows') or []
        got = results[no]['rows']
        ok, why = rowset_eq(got, want)
        if not ok:
            problems.append('Q%d 实测与期望表不符：%s（实测 %s / 期望 %s）' % (no, why, got, want))
        if exp[no].get('question') and exp[no]['question'].strip() != items[no]['question'].strip():
            problems.append('Q%d 问题文本与期望表不一致：%r vs %r'
                            % (no, items[no]['question'], exp[no]['question']))
    return exp, problems


def table_stats(conn):
    out = {}
    with conn.cursor() as cur:
        for t in ('ads_trade_overview_di', 'ads_channel_conversion_di',
                  'ads_region_gmv_di', 'ads_product_sales_rank_di'):
            cur.execute('SELECT COUNT(*) c, MIN(dt) a, MAX(dt) b FROM %s.%s' % (SCHEMA, t))
            r = cur.fetchone()
            out[t] = dict(rows=int(r['c']), dt_min=str(normalize(r['a'])), dt_max=str(normalize(r['b'])))
        cur.execute('SELECT MAX(dt) m FROM %s.ads_trade_overview_di' % SCHEMA)
        anchor = str(normalize(cur.fetchone()['m']))
        # VERSION() 在 StarRocks 返回的是 MySQL 协议兼容版本号（5.1.0），真实内核版本要用 current_version()
        cur.execute('SELECT current_version() v')
        version = cur.fetchone()['v']
    return out, anchor, version


def order_stability(items):
    """两轮独立执行，记录同一题两轮行序是否完全一致。
    只作证据留档：DETAIL 行序理论上不确定，判定一律集合语义（评测集 §5），这里不改判定。"""
    rounds = []
    for _ in range(2):
        res, fails = run_each(items)
        if fails:
            raise SystemExit('行序稳定性采样执行失败：%s' % fails)
        rounds.append(res)
    stable = {}
    for no in sorted(items):
        a = [json.dumps(r, ensure_ascii=False, sort_keys=True) for r in rounds[0][no]['rows']]
        b = [json.dumps(r, ensure_ascii=False, sort_keys=True) for r in rounds[1][no]['rows']]
        stable[no] = a == b
    return stable


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true', help='只校验，不写 eval/golden_set.json')
    args = ap.parse_args()

    items = parse_doc()
    results, failures = run_each(items)
    if failures:
        for f in failures:
            print('FAIL ' + f)
        raise SystemExit('有 %d 条 expect_sql 未能独立执行成功（票面要求 15/15）' % len(failures))
    print('实测：15/15 条 expect_sql 独立执行成功')

    exp, problems = cross_check(items, results)
    if problems:
        for p in problems:
            print('FAIL ' + p)
        raise SystemExit('实测结果与 qa/eval_expectations.json 不符，拒绝生成')
    print('交叉校验：15/15 与 qa/eval_expectations.json 一致（集合语义，容差 %.3f）' % TOL)

    # 类型：形状推断 vs 票面声明
    type_problems = []
    types = {}
    for no in sorted(items):
        got = infer_type(results[no]['rows'], results[no]['cols'])
        types[no] = got
        if got is None:
            type_problems.append('Q%d 结果为空/无列，无法判定 expect_type' % no)
        elif got != DECLARED_TYPE[no]:
            type_problems.append('Q%d 形状推断 %s ≠ 票面声明 %s（实测 %d 行 %d 列）'
                                 % (no, got, DECLARED_TYPE[no], len(results[no]['rows']), len(results[no]['cols'])))
    if type_problems:
        for p in type_problems:
            print('FAIL ' + p)
        raise SystemExit('expect_type 与票面不一致，拒绝生成')

    stable = order_stability(items)

    conn = pymysql.connect(**DB)
    try:
        stats, anchor, version = table_stats(conn)
    finally:
        conn.close()

    out_items = []
    for no in sorted(items):
        it = items[no]
        e = exp.get(no, {})
        et = types[no]
        rec = {
            'no': no,
            'difficulty': DIFFICULTY[no],
            'question': it['question'],
            'expect_type': et,
            'expect_sql': it['expect_sql'],
            'expect_cols': results[no]['cols'],
            'expect_rows': results[no]['rows'],
            'expect_value': build_value(et, results[no]),
        }
        if e.get('sqlMust'):
            rec['sql_must'] = e['sqlMust']
        if e.get('window'):
            rec['window'] = e['window']
        out_items.append(rec)

    doc = {
        'schema_version': '1.0',
        'ticket': '06a',
        'generated_at': datetime.now().astimezone().isoformat(timespec='seconds'),
        'generator': 'eval/gen_golden_set.py',
        'sources': {
            'questions_and_expect_sql': {'path': 'docs/评测集.md', 'sha256': sha256_full(DOC)},
            'sql_must_and_window': {'path': 'qa/eval_expectations.json', 'sha256': sha256_full(EXPECT)},
        },
        'target': {
            'db': SCHEMA,
            'starrocks_version': version,
            'anchor': anchor,
            'tables': stats,
            # 行序只作采样证据，不进逐题字段（QA-06a P2-5）：Q8 并列行 30 次采样是 50/50 翻转，
            # 逐题写 true/false 会让后来的人以为「这题稳定」而按位置比。
            'row_order_sampling': {
                'sampled_at': datetime.now().astimezone().isoformat(timespec='seconds'),
                'rounds': 2,
                'stable': [no for no in sorted(stable) if stable[no]],
                'unstable': [no for no in sorted(stable) if not stable[no]],
                'note': '仅证据不作判据；判定一律集合语义（judge_rules.rows）',
            },
        },
        'tolerance': TOL,
        'type_rules': {
            'scalar': '恰好 1 行 1 列；expect_value 为数字/字符串',
            'stringset': '多行 1 列；expect_value 为一维数组',
            'rowset': '其余（含 1 行多列）；expect_value 为对象数组',
        },
        'judge_rules': {
            'rows': '一律集合语义（同行、同值、容差 0.011），不按行序判对；行序只在 target.row_order_sampling 留采样证据',
            'cols': '06b 只校验列数，不比列名（生成器用 metricId 做别名，与标准 SQL 别名不同）',
            'window': '窗口题除行级对拍外必须断言 SQL 形状（禁 BETWEEN、含 dt > ? 与 dt <= ?、参数为 [anchor-days, anchor]）',
        },
        'items': out_items,
    }

    print('\n类型分布：%s' % ', '.join('%s=%d' % (t, sum(1 for v in types.values() if v == t))
                                   for t in ('scalar', 'stringset', 'rowset')))
    print('锚点 %s（%s）| StarRocks %s' % (anchor, SCHEMA, version))
    stable_n = sum(1 for v in stable.values() if v)
    if stable_n == len(stable):
        print('行序两轮一致：%d/15（仍按集合语义判定，行序不作判据）' % stable_n)
    else:
        print('行序两轮一致：%d/15，不一致的题：%s（符合预期，判定一律集合语义）'
              % (stable_n, [n for n, v in stable.items() if not v]))

    if args.dry_run:
        print('\n--dry-run：未写盘')
        return

    with open(OUT, 'w', encoding='utf-8') as fp:
        json.dump(doc, fp, ensure_ascii=False, indent=2)
        fp.write('\n')
    print('\n已写出 %s（%d 字节，15 题）' % (os.path.relpath(OUT, REPO), os.path.getsize(OUT)))


if __name__ == '__main__':
    sys.exit(main())

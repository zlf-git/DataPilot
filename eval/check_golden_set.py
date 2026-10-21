# -*- coding: utf-8 -*-
"""
Ticket 06a 校验器：eval/golden_set.json 结构 + 形状 + 可重放性。

三件事，任何一条不满足都退出码 1：
  1. 结构校验：15 题齐全、字段完整、expect_type 只取三值。
  2. 形状校验：expect_value 形状必须与 expect_type 匹配（scalar→数字/字符串、stringset→数组、
     rowset→对象数组），expect_cols / expect_rows 的键必须对齐。
  3. 可重放校验（--replay）：把 expect_sql 再独立执行一遍，与落盘的 expect_rows 对拍
     —— 这是「expect_rows 不是手抄，是实测」的最终证据。

用法：
  python eval/check_golden_set.py             # 只做 1+2
  python eval/check_golden_set.py --replay    # 加做 3（需 StarRocks 可达）
退出码：0 = 全通过；1 = 有 FAIL
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(HERE, 'golden_set.json')
TOL = 0.011
TYPES = {'scalar', 'stringset', 'rowset'}
REQUIRED_ITEM = ('no', 'question', 'expect_type', 'expect_sql', 'expect_cols', 'expect_rows', 'expect_value')
REQUIRED_TOP = ('schema_version', 'target', 'tolerance', 'items')
DB = dict(host='127.0.0.1', port=9030, user='root', password='', charset='utf8mb4',
          connect_timeout=10, read_timeout=30)


def fail(fails, msg):
    fails.append(msg)


def check_shape(it, fails):
    no, et = it['no'], it['expect_type']
    rows, cols, val = it['expect_rows'], it['expect_cols'], it['expect_value']
    tag = 'Q%d' % no

    if et not in TYPES:
        fail(fails, '%s expect_type=%r 不在 %s' % (tag, et, sorted(TYPES)))
        return

    if not expect_list(it, 'expect_cols', fails, tag):
        return
    if not isinstance(rows, list):
        fail(fails, '%s expect_rows 必须是数组' % tag)
        return

    for i, r in enumerate(rows):
        if not isinstance(r, dict):
            fail(fails, '%s expect_rows[%d] 必须是对象' % (tag, i))
            return
        if list(r.keys()) != list(cols):
            fail(fails, '%s expect_rows[%d] 键 %s ≠ expect_cols %s（列序须一致）'
                 % (tag, i, list(r.keys()), cols))
            return

    if et == 'scalar':
        if not isinstance(val, (int, float, str)) or isinstance(val, bool):
            fail(fails, '%s scalar 的 expect_value 必须是数字或字符串，实际 %r' % (tag, val))
        if len(rows) != 1 or len(cols) != 1:
            fail(fails, '%s scalar 要求 expect_rows 恰好 1 行 1 列，实际 %d 行 %d 列'
                 % (tag, len(rows), len(cols)))
        elif not cell_eq(rows[0][cols[0]], val):
            fail(fails, '%s scalar 的 expect_value=%r ≠ expect_rows 首格 %r'
                 % (tag, val, rows[0][cols[0]]))

    elif et == 'stringset':
        if not isinstance(val, list) or not all(isinstance(x, (str, int, float)) and not isinstance(x, bool)
                                               for x in val):
            fail(fails, '%s stringset 的 expect_value 必须是一维数组，实际 %r' % (tag, val))
            return
        if len(cols) != 1:
            fail(fails, '%s stringset 要求 expect_rows 只有 1 列，实际 %s' % (tag, cols))
            return
        got = []
        for r in rows:
            v = r[cols[0]]
            if v not in got:
                got.append(v)
        if len(got) != len(val) or not all(any(cell_eq(g, w) for g in got) for w in val):
            fail(fails, '%s stringset expect_value %s ≠ 实测列值 %s' % (tag, val, got))

    else:  # rowset
        if not isinstance(val, list) or not all(isinstance(x, dict) for x in val):
            fail(fails, '%s rowset 的 expect_value 必须是对象数组，实际 %r' % (tag, type(val).__name__))
            return
        if json.dumps(val, sort_keys=True, ensure_ascii=False) != json.dumps(rows, sort_keys=True, ensure_ascii=False):
            fail(fails, '%s rowset 的 expect_value 必须等于 expect_rows 全量' % tag)


def expect_list(it, key, fails, tag):
    v = it.get(key)
    if not isinstance(v, list) or not v:
        fail(fails, '%s %s 必须是非空数组' % (tag, key))
        return False
    if not all(isinstance(x, str) and x for x in v):
        fail(fails, '%s %s 只能是非空字符串数组' % (tag, key))
        return False
    if len(set(v)) != len(v):
        fail(fails, '%s %s 有重复列名' % (tag, key))
        return False
    return True


def cell_eq(a, b, tol=TOL):
    if isinstance(a, bool) or isinstance(b, bool):
        return a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(a) - float(b)) <= tol
    return str(a) == str(b)


def rowset_eq(actual, expected, tol=TOL):
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


def replay(items, fails):
    import pymysql
    from decimal import Decimal
    import datetime as dtmod

    def norm(v):
        if isinstance(v, Decimal):
            return float(v)
        if isinstance(v, dtmod.datetime):
            return v.strftime('%Y-%m-%d %H:%M:%S') if (v.hour or v.minute or v.second) else v.strftime('%Y-%m-%d')
        if isinstance(v, dtmod.date):
            return v.strftime('%Y-%m-%d')
        return v

    for it in items:
        tag = 'Q%d' % it['no']
        try:
            conn = pymysql.connect(**DB)
            try:
                conn.select_db('mall_dw')
                with conn.cursor() as cur:
                    cur.execute(it['expect_sql'])
                    cols = [d[0] for d in cur.description]
                    rows = [{c: norm(r[i]) for i, c in enumerate(cols)} for r in cur.fetchall()]
            finally:
                conn.close()
        except Exception as e:  # noqa: BLE001
            fail(fails, '%s 重放执行失败：%s' % (tag, e))
            continue
        if cols != it['expect_cols']:
            fail(fails, '%s 重放列名 %s ≠ 落盘 %s' % (tag, cols, it['expect_cols']))
        ok, why = rowset_eq(rows, it['expect_rows'])
        if not ok:
            fail(fails, '%s 重放结果与落盘 expect_rows 不符：%s' % (tag, why))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--replay', action='store_true', help='重跑 expect_sql 与落盘 expect_rows 对拍')
    ap.add_argument('--file', default=OUT, help='待校验文件，默认 eval/golden_set.json')
    args = ap.parse_args()
    target = args.file

    if not os.path.exists(target):
        raise SystemExit('缺 %s，先跑 eval/gen_golden_set.py' % target)
    with open(target, encoding='utf-8') as fp:
        doc = json.load(fp)

    fails = []
    for k in REQUIRED_TOP:
        if k not in doc:
            fail(fails, '顶层缺字段 %s' % k)
    items = doc.get('items') or []
    if len(items) != 15:
        fail(fails, '题数 %d ≠ 15' % len(items))
    nos = [it.get('no') for it in items if isinstance(it, dict)]
    if sorted(nos) != list(range(1, 16)):
        fail(fails, '题号不是 1..15 齐全无重复：%s' % nos)

    for it in items:
        if not isinstance(it, dict):
            fail(fails, 'items 里有非对象元素')
            continue
        tag = 'Q%s' % it.get('no')
        for k in REQUIRED_ITEM:
            if k not in it:
                fail(fails, '%s 缺字段 %s' % (tag, k))
        sql = it.get('expect_sql') or ''
        if not sql.upper().lstrip().startswith('SELECT'):
            fail(fails, '%s expect_sql 不以 SELECT 开头' % tag)
        if '--' in sql:
            fail(fails, '%s expect_sql 残留注释，无法独立执行' % tag)
        if not isinstance(it.get('row_order_stable'), bool):
            fail(fails, '%s row_order_stable 必须是 bool' % tag)
        if any(k in it for k in REQUIRED_ITEM):
            check_shape(it, fails)
        win = it.get('window')
        if win:
            for k in ('days', 'from', 'anchor'):
                if k not in win:
                    fail(fails, '%s window 缺 %s' % (tag, k))
            must = [m.upper() for m in it.get('sql_must') or []]
            for frag in ('DT > ?', 'DT <= ?'):
                if frag not in must:
                    fail(fails, '%s 窗口题 sql_must 缺 %s（QA-04 P1-2 硬规则）' % (tag, frag))

    if args.replay:
        replay(items, fails)

    print('== golden_set.json 校验 ==')
    print('  路径      %s' % os.path.relpath(os.path.abspath(target), REPO))
    print('  生成时间  %s' % doc.get('generated_at'))
    print('  来源      %s' % '; '.join('%s=%s' % (k, v) for k, v in (doc.get('sources') or {}).items()))
    print('  目标      %s@%s（%s）' % (doc.get('target', {}).get('db'), doc.get('target', {}).get('anchor'),
                                   doc.get('target', {}).get('starrocks_version')))
    print('  题数      %d' % len(items))
    dist = {}
    for it in items:
        dist[it.get('expect_type')] = dist.get(it.get('expect_type'), 0) + 1
    print('  类型分布  %s' % ', '.join('%s=%d' % (k, dist.get(k, 0)) for k in ('scalar', 'stringset', 'rowset')))
    print('  窗口题    %d 条' % sum(1 for it in items if it.get('window')))
    print('  重放校验  %s' % ('已执行（15 条 expect_sql 重跑对拍）' if args.replay else '跳过（加 --replay 执行）'))

    if fails:
        print('\n== FAIL %d 条 ==' % len(fails))
        for f in fails:
            print('  - ' + f)
        return 1
    print('\n结果：PASS（结构 15/15 + 形状匹配%s）' % (' + 重放一致 15/15' if args.replay else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())

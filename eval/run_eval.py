# -*- coding: utf-8 -*-
"""
Ticket 06b：评测脚本 run_eval.py

读 `eval/golden_set.json`（06a 产出的机读真源）→ 逐题打 `/api/ask` → 按 expect_type 对拍
→ 出六层指标 + 逐题 verdict → 落 `eval/result.json`。

判定口径（docs/评测集.md §5 + 06a 实测回填）
------------------------------------------------
答对 := 链路可用（sql 非空、无拒绝文案）+ 列数一致 + 结果集合一致 + 窗口题 SQL 形状合规
  · scalar    恰好 1 行 1 列，数值 abs 差 ≤ tolerance（默认 0.011）
  · stringset 单列多行，集合相等（忽略顺序）
  · rowset    行数一致 + 行按集合（multiset）匹配，**不按行序判对**
              （Q8 两轮采样到过 `人体工学椅/机械键盘` 顺序翻转，按位置比会误判）
              行内比法：实际列名集合与 expect_cols 一致→按列名比；否则→按行内值多重集比
              （生成器用 metricId 做别名，Q2 输出 `paid_order_cnt` 而标准 SQL 是 `total_paid`）
  · 列名一律不比，只比列数（审计 P1#5 / 06a judge_rules）

窗口题（golden_set 里 `window` 非空）**必须断言 SQL 形状**，值对拍不足以判别半开/闭区间：
  ① 不含 BETWEEN ② 同时含 `dt > ?` 与 `dt <= ?` ③ 参数含 from 与 anchor 两个日期且不含第三个
  （2026-10-12 是空分区，闭区间 8 天与半开 7 天逐题等值 —— QA-04 P1-2）
  另有值级探针 `probe_window3`：半开 3 天 157702.85 vs 闭区间 4 天 223887.35，单列回归项，
  不计入 15 题准确率，但 FAIL 时整体结论不得判 PASS。

六层指标
--------
1. 准确率        pass / 总题数
2. 表命中率      实际 SQL 引用的表集合 == 标准 SQL 引用的表集合 的题数占比
                 （不看 SQL 文本写法：生成器用标量子查询、标准答案用 JOIN，表集合本就不同题不同）
3. 字段命中率    实际 SQL 引用到的白名单物理字段集合 == 标准 SQL 的 的题数占比
4. 平均延迟      /api/ask 返回的 costMs 均值
5. 语法通过率    sql 非空 + 语法闸门未 REJECT + 执行出结果 的题数占比
6. 闸门拦截率    任一闸门 REJECT 的题数占比（golden_set 全是有答案的题，正常应 0）

`sql_must` 只做**软**检查：逐条子串命中情况计入 `sql_shape_score` 并在逐题里列出 `sql_must_miss`，
不判 FAIL —— 票面明确「标准 SQL 与生成器结果一致即可，写法可不同」，硬判会把等价写法误杀。
窗口题的形状断言是硬规则，不受此豁免。

用法
----
  python eval/run_eval.py --host http://127.0.0.1:18080
  python eval/run_eval.py --host http://127.0.0.1:18080 --out eval/result.json
退出码：0 = 15 题全 pass 且窗口探针通过；1 = 否则（含 host 不可达）
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
GOLDEN = os.path.join(HERE, 'golden_set.json')
PROBE_IR = os.path.join(ROOT, 'src', 'main', 'resources', 'stub_ir', 'probe_window3.json')
DEFAULT_OUT = os.path.join(HERE, 'result.json')

# probe_window3 的两条值：半开 3 天 / 闭区间 4 天（10-17~10-19 vs 10-16~10-19）
PROBE_EXPECT = 157702.85
PROBE_CLOSED = 223887.35


# ---------------------------------------------------------------- 基础设施

# 绕过环境变量里的 HTTP(S)_PROXY：评测目标是本机服务，走代理会拿到 502 上游错误，
# 把「服务没起」伪装成「网关故障」（实测 env 代理对 127.0.0.1:19999 返回 502）
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_json(url, payload=None, timeout=30):
    data = None if payload is None else json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', 'replace')
        raise RuntimeError('HTTP %s %s：%s' % (e.code, url, body[:200]))
    except urllib.error.URLError as e:
        raise RuntimeError('连不上 %s：%s（服务起了吗？）' % (url, e.reason))


def sha256_of(path):
    with open(path, 'rb') as fp:
        return hashlib.sha256(fp.read()).hexdigest()


def git(*args):
    try:
        return subprocess.run(['git', '-C', ROOT] + list(args), capture_output=True,
                              text=True, timeout=10).stdout.strip()
    except Exception as e:  # noqa: BLE001
        return '<%s>' % e


def dirty_files(exclude=None):
    """工作区未提交改动数。默认把本脚本自己的输出文件排除：result.json 是被写的产物，
    否则「跑一次评测 → 文件变脏 → 记录 dirty=1」会让归档的脏文件数恒为正，失去信息量。

    注意：git() 会对整体输出做 strip，首行的前导空格（porcelain 的 XY 位）因此丢失，
    不能按固定下标切路径——这里按「第一个空白前是状态、其余是路径」解析。
    """
    lines = [l for l in git('status', '--porcelain').splitlines() if l.strip()]
    if not exclude:
        return len(lines)
    rel = os.path.relpath(exclude, ROOT).replace('\\', '/')
    kept = []
    for l in lines:
        s = l.strip()
        parts = s.split(None, 1)
        path = parts[1].strip().strip('"').replace('\\', '/') if len(parts) > 1 else ''
        if path.endswith(rel):
            continue
        kept.append(l)
    return len(kept)


def norm_cell(v):
    """日期列可能被序列化成 epoch 毫秒；Decimal 已被生成器转 float，这里只兜残余类型"""
    if isinstance(v, bool) or v is None:
        return v
    if isinstance(v, (int, float)) and 1e11 < abs(float(v)) < 1e13:
        return datetime.fromtimestamp(float(v) / 1000).strftime('%Y-%m-%d')
    return v


def cell_eq(got, want, tol):
    got, want = norm_cell(got), norm_cell(want)
    if isinstance(want, bool) or isinstance(got, bool):
        return got == want
    if isinstance(want, (int, float)):
        try:
            return abs(float(got) - float(want)) <= tol
        except (TypeError, ValueError):
            return False
    return str(got) == str(want)


def cell_key(v, tol):
    """多重集比较用一个可哈希的键：数值按容差无法哈希，用四舍五入到 0.001 兜（tolerance 量级远小于它）"""
    v = norm_cell(v)
    if isinstance(v, bool) or v is None:
        return ('lit', repr(v))
    if isinstance(v, (int, float)):
        return ('num', round(float(v), 3))
    return ('str', str(v))


# ---------------------------------------------------------------- SQL 解析

TABLE_RE = re.compile(r'\b(?:FROM|JOIN)\s+([A-Za-z_][A-Za-z0-9_.]*)', re.IGNORECASE)
ALIAS_RE = re.compile(r'\bAS\s+([A-Za-z_][A-Za-z0-9_]*)', re.IGNORECASE)


def extract_tables(sql):
    out = set()
    for m in TABLE_RE.finditer(sql or ''):
        out.add(m.group(1).split('.')[-1].lower())
    return out


def extract_fields(sql, whitelist):
    """只认「物理字段引用」，别名不算。分两步：

    ① SELECT 列表里的 `AS 别名` 声明整段抹掉（别名声明本身不是字段引用）；
    ② `ORDER BY` / `GROUP BY` / `HAVING` 里常复用别名（如 Q11 `ORDER BY total_gmv DESC`），只在这几段抹。

    踩过两次坑（06b 首轮实跑）：
      · 不抹别名 → Q11/Q12 标准 SQL 的 `AS total_gmv` 正好是另一张表的真实字段名，误判 13/15；
      · 全文抹别名 → Q2/Q10 的 `SUM(paid_order_cnt) AS paid_order_cnt` 别名与自身字段同名，真引用被一起抹掉，仍 13/15。
    分两步后两侧同规则，别名与字段同名也不会互相吃掉。
    """
    text = sql or ''
    aliases = {m.group(1).lower() for m in ALIAS_RE.finditer(text)}
    body = ALIAS_RE.sub(' ', text)
    m = re.search(r'\b(?:ORDER\s+BY|GROUP\s+BY|HAVING)\b', body, re.IGNORECASE)
    head, tail = (body[:m.start()], body[m.start():]) if m else (body, '')
    for a in aliases:
        tail = re.sub(r'\b%s\b' % re.escape(a), ' ', tail)
    low = (head + ' ' + tail).lower()
    return {f for f in whitelist if re.search(r'\b%s\b' % re.escape(f), low)}


def window_issues(sql, params, win):
    """硬规则：禁 BETWEEN、含 dt > ? 与 dt <= ?、参数含 from/anchor"""
    issues = []
    up = (sql or '').upper()
    if 'BETWEEN' in up:
        issues.append('SQL 出现 BETWEEN（闭区间会多取一天）')
    if not re.search(r'\bDT\s*>\s*\?', up):
        issues.append('缺 `dt > ?` 半开下界')
    if not re.search(r'\bDT\s*<=\s*\?', up):
        issues.append('缺 `dt <= ?` 锚点上界')
    ps = [str(p) for p in (params or [])]
    for label, want in (('下界', win.get('from')), ('锚点', win.get('anchor'))):
        if want and want not in ps:
            issues.append('参数缺%s %s（实际 %s）' % (label, want, ps))
    extra = [p for p in ps if p not in (win.get('from'), win.get('anchor'))]
    if extra:
        issues.append('参数多出 %s（窗口题只该有下界+锚点）' % extra)
    return issues


# ---------------------------------------------------------------- 行级对拍

def compare(expect_type, item, rows, tol):
    """返回 (ok, reason, detail)"""
    exp_cols = item.get('expect_cols') or []
    if not rows:
        return False, 'empty_rows', '结果 0 行'
    if len(rows[0]) != len(exp_cols):
        return False, 'col_count_mismatch', '列数 %d ≠ 期望 %d（实际列 %s）' % (
            len(rows[0]), len(exp_cols), list(rows[0].keys()))

    if expect_type == 'scalar':
        if len(rows) != 1:
            return False, 'row_count_mismatch', '标量题返回 %d 行' % len(rows)
        got = list(rows[0].values())[0]
        want = item.get('expect_value')
        if isinstance(want, (int, float)) and got is not None:
            try:
                if abs(float(got) - float(want)) <= tol:
                    return True, 'ok', '%s' % got
            except (TypeError, ValueError):
                pass
        if not isinstance(want, (int, float)) and str(got) == str(want):
            return True, 'ok', '%s' % got
        return False, 'value_mismatch', '实际 %r ≠ 期望 %r' % (got, want)

    if expect_type == 'stringset':
        got = {str(norm_cell(list(r.values())[0])) for r in rows}
        want = {str(x) for x in (item.get('expect_value') or [])}
        if got == want:
            return True, 'ok', '%d 项' % len(got)
        return False, 'value_mismatch', '多出 %s / 缺少 %s' % (sorted(got - want), sorted(want - got))

    # rowset：先比行数，再按集合比
    exp_rows = item.get('expect_rows') or []
    if len(rows) != len(exp_rows):
        return False, 'row_count_mismatch', '行数 %d ≠ 期望 %d' % (len(rows), len(exp_rows))

    same_names = set(rows[0].keys()) == set(exp_cols)
    remaining = list(rows)
    for e in exp_rows:
        hit = None
        for r in remaining:
            if same_names:
                ok = all(cell_eq(r.get(k), v, tol) for k, v in e.items())
            else:
                ok = (sorted(cell_key(v, tol) for v in r.values())
                      == sorted(cell_key(v, tol) for v in e.values()))
            if ok:
                hit = r
                break
        if hit is None:
            return False, 'value_mismatch', '缺期望行 %s（实际 %s）' % (e, rows[:4])
        remaining.remove(hit)
    return True, 'ok', '%d 行（%s）' % (len(rows), '按列名' if same_names else '按值多重集')


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='http://127.0.0.1:18080', help='/api/ask 所在地址')
    ap.add_argument('--out', default=DEFAULT_OUT, help='result.json 落盘路径')
    ap.add_argument('--timeout', type=float, default=30.0)
    ap.add_argument('--expect-total', type=int, default=15,
                    help='评分题数下限；golden_set 少题直接失败，避免「删掉难题就全绿」')
    args = ap.parse_args()
    host = args.host.rstrip('/')

    if not os.path.exists(GOLDEN):
        raise SystemExit('缺 %s，先跑 eval/gen_golden_set.py' % GOLDEN)
    with open(GOLDEN, encoding='utf-8') as fp:
        golden = json.load(fp)
    items = golden['items']
    if len(items) != args.expect_total:
        raise SystemExit('golden_set 题数 %d ≠ %d：真源不完整，拒绝出评测结论（删题不能变相提分）'
                         % (len(items), args.expect_total))
    tol = float(golden.get('tolerance', 0.011))
    golden_sha = sha256_of(GOLDEN)

    health = http_json(host + '/api/health/metadata', timeout=args.timeout)
    fields = http_json(host + '/api/metadata/fields', timeout=args.timeout)
    whitelist = {(f.get('fieldName') or f.get('field_name') or '').lower()
                 for f in fields} - {''}

    print('== 环境 ==')
    print('  host            %s' % host)
    print('  元数据          %s' % json.dumps(health, ensure_ascii=False))
    print('  白名单字段      %d 个' % len(whitelist))
    print('  golden_set      %s（sha256:%s）' % (os.path.relpath(GOLDEN, ROOT), golden_sha[:12]))
    _dirty = dirty_files(exclude=args.out)
    print('  被测代码        %s%s' % (
        git('log', '-1', '--oneline') or '<非 git 环境>',
        '（工作区 %d 处未提交改动）' % _dirty if _dirty else ''))
    print('  真源锚点        %s（生成于 %s）' % (
        (golden.get('target') or {}).get('anchor'), golden.get('generated_at')))

    print('\n== 15 题逐题判定 ==')
    print('  %-3s %-9s %-6s %-18s %s' % ('#', 'type', '列/行', 'verdict', '说明'))

    results = []
    n_pass = 0
    table_hit = field_hit = syntax_ok = gate_blocked = 0
    latencies = []
    sql_shape_total = sql_shape_hit = 0

    for it in items:
        no = it['no']
        resp = http_json(host + '/api/ask', {'question': it['question']}, timeout=args.timeout)
        sql = (resp.get('sql') or '').strip()
        answer = (resp.get('answer') or '').strip()
        status = (resp.get('status') or '').strip()
        rows = resp.get('rows') or []
        params = resp.get('params') or []
        gates = resp.get('gates') or []
        latencies.append(resp.get('costMs') or 0)

        # Ticket 11 起 answer 成功时也有内容（解释节点），判据改用显式 status；
        # 老响应没有 status 时回退到「answer 为空」这条旧口径
        ran = bool(sql) and (status == 'SUCCESS' if status else not answer)
        syntax_ok += 1 if ran else 0
        gate_blocked += 1 if any('REJECT' in g for g in gates) else 0

        issues = []
        if not sql:
            issues.append(('gate_blocked', 'sql 为空，answer=%r' % answer[:60]))
        elif not ran:
            issues.append(('exec_failed', 'status=%s answer=%r（执行异常或闸门拦截）'
                           % (status or '未回显', answer[:60])))
        else:
            ok, reason, detail = compare(it['expect_type'], it, rows, tol)
            if not ok:
                issues.append((reason, detail))
            if it.get('window'):
                w = window_issues(sql, params, it['window'])
                if w:
                    issues.append(('window_shape_mismatch', '；'.join(w)))

        # 软检查：形状与表/字段命中（不判 FAIL，但计入指标与展示）
        must = it.get('sql_must') or []
        up = sql.upper()
        miss = [m for m in must if m.upper() not in up]
        sql_shape_total += len(must)
        sql_shape_hit += len(must) - len(miss)

        exp_tables = extract_tables(it.get('expect_sql') or '')
        act_tables = extract_tables(sql)
        t_hit = bool(sql) and exp_tables == act_tables
        table_hit += 1 if t_hit else 0

        exp_fields = extract_fields(it.get('expect_sql') or '', whitelist)
        act_fields = extract_fields(sql, whitelist)
        f_hit = bool(sql) and exp_fields == act_fields
        field_hit += 1 if f_hit else 0

        passed = not issues
        n_pass += 1 if passed else 0
        verdict = 'PASS' if passed else 'FAIL'
        if issues:
            detail = '；'.join('[%s] %s' % (r, d) for r, d in issues)
        else:
            detail = (compare(it['expect_type'], it, rows, tol)[2] if rows else '')
        print('  %-3d %-9s %-6s %-18s %s' % (
            no, it['expect_type'], '%d列/%d行' % (len(rows[0]) if rows else 0, len(rows)),
            verdict, detail[:80]))

        results.append({
            'no': no, 'question': it['question'], 'difficulty': it.get('difficulty'),
            'expect_type': it['expect_type'], 'pass': passed, 'verdict': verdict,
            'reasons': [{'reason': r, 'detail': d} for r, d in issues],
            'actual_rows': len(rows), 'actual_cols': list(rows[0].keys()) if rows else [],
            'expect_rows_n': len(it.get('expect_rows') or []),
            'table_hit': t_hit, 'field_hit': f_hit,
            'expect_tables': sorted(exp_tables), 'actual_tables': sorted(act_tables),
            'field_diff': {'missing': sorted(exp_fields - act_fields),
                           'extra': sorted(act_fields - exp_fields)},
            'sql_must_miss': miss,
            'window': it.get('window'),
            'sql': sql, 'params': params, 'gates': gates,
            'cost_ms': resp.get('costMs'), 'trace_id': resp.get('traceId'),
            'answer': answer, 'rows': rows,
        })

    # 窗口值级探针（独立回归项，不计入 15 题准确率）
    probe = {'name': 'probe_window3', 'expect': PROBE_EXPECT, 'closed_interval_value': PROBE_CLOSED}
    try:
        with open(PROBE_IR, encoding='utf-8') as fp:
            probe['question'] = json.load(fp)['question']
        pr = http_json(host + '/api/ask', {'question': probe['question']}, timeout=args.timeout)
        prow = pr.get('rows') or []
        val = list(prow[0].values())[0] if prow else None
        probe.update({'sql': pr.get('sql'), 'params': pr.get('params'), 'actual': val,
                      'pass': isinstance(val, (int, float)) and abs(float(val) - PROBE_EXPECT) <= tol})
        probe['note'] = ('命中半开区间语义' if probe['pass']
                         else '值 %r ≠ %s' % (val, PROBE_EXPECT)
                         + ('（≈闭区间 %s，说明多吃了一天）' % PROBE_CLOSED
                            if isinstance(val, (int, float)) and abs(float(val) - PROBE_CLOSED) <= tol else ''))
    except Exception as e:  # noqa: BLE001
        probe.update({'pass': False, 'note': '探针执行失败：%s' % e})

    total = len(items)
    metrics = {
        'accuracy': round(n_pass / total, 4),
        'table_hit_rate': round(table_hit / total, 4),
        'field_hit_rate': round(field_hit / total, 4),
        'avg_latency_ms': round(sum(latencies) / total, 1),
        'syntax_pass_rate': round(syntax_ok / total, 4),
        'gate_block_rate': round(gate_blocked / total, 4),
        # 附加观测项（不属于六层，但 06b 交付里要能看见形状质量）
        'sql_shape_score': round(sql_shape_hit / sql_shape_total, 4) if sql_shape_total else 1.0,
        'window_probe_pass': bool(probe.get('pass')),
    }

    print('\n== 六层指标 ==')
    print('  准确率        %d/%d = %.2f%%' % (n_pass, total, metrics['accuracy'] * 100))
    print('  表命中率      %d/%d = %.2f%%' % (table_hit, total, metrics['table_hit_rate'] * 100))
    print('  字段命中率    %d/%d = %.2f%%' % (field_hit, total, metrics['field_hit_rate'] * 100))
    print('  平均延迟      %.1f ms' % metrics['avg_latency_ms'])
    print('  语法通过率    %d/%d = %.2f%%' % (syntax_ok, total, metrics['syntax_pass_rate'] * 100))
    print('  闸门拦截率    %d/%d = %.2f%%' % (gate_blocked, total, metrics['gate_block_rate'] * 100))
    print('  ── 附加 ──')
    print('  SQL 形状分    %d/%d = %.2f%%（软检查，sql_must 子串命中）'
          % (sql_shape_hit, sql_shape_total, metrics['sql_shape_score'] * 100))
    print('  窗口探针      %s  %s' % ('PASS' if probe.get('pass') else 'FAIL', probe.get('note', '')))

    overall = n_pass == total and bool(probe.get('pass'))
    print('\n== 结论 ==')
    print('  %s（15 题全 pass 且窗口探针通过才算 PASS）' % ('PASS' if overall else 'FAIL'))
    if not overall:
        bad = [r['no'] for r in results if not r['pass']]
        print('  未通过题：%s' % (bad or '无（15 题全过，探针挂）'))

    out = {
        'schema_version': '1.0',
        'ticket': '06b',
        'generated_at': datetime.now().astimezone().isoformat(timespec='seconds'),
        'host': host,
        'golden_set': {'path': os.path.relpath(GOLDEN, ROOT), 'sha256': golden_sha,
                       'generated_at': golden.get('generated_at'),
                       'anchor': (golden.get('target') or {}).get('anchor'),
                       'tolerance': tol},
        'code': {'git_head': git('log', '-1', '--oneline'),
                 'dirty_files': dirty_files(exclude=args.out)},
        'env': {'health': health, 'whitelist_fields': len(whitelist)},
        'metrics': metrics,
        'overall': 'PASS' if overall else 'FAIL',
        'window_probe': probe,
        'items': results,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as fp:
        json.dump(out, fp, ensure_ascii=False, indent=2)
    print('  已落盘 %s' % os.path.relpath(args.out, ROOT))
    return 0 if overall else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except RuntimeError as e:
        print('ERROR: %s' % e, file=sys.stderr)
        sys.exit(1)

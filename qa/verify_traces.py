# -*- coding: utf-8 -*-
"""
Ticket 09 端到端验收：AgentOps 埋点。

判据不写死 span 数量：基线从 GET /api/health/traces 的 expectedSpansPerSuccess 取
（源码里 = TraceSpan.values().length），加节点时这里自动跟随 —— 票面明确要求
「step 数 ≥ 当前装配的 Step 数，不写死 8」。

用法：python verify_traces.py [host]     默认 http://127.0.0.1:18080
退出码：0 通过 / 1 断言失败 / 2 服务不可达

检查项：
  1. 一次成功问数后 agent_run 存在，status=SUCCESS
  2. latencyMs > 0、tokens > 0、cost > 0（桩固定 500/100 token，成本必须真算出来不是 0）
  3. step 数 ≥ 当前装配基线，且 stepOrder 从 1 起严格递增
  4. span_type 里 llm / sql / gate 各至少 1 条（票面硬要求）
  5. llm span 的 tokenUsed > 0（用量记到具体节点上，不只在 run 汇总）
  6. agent_run.final_sql 记录的是半开区间 SQL（有 dt > ? 与 dt <= ?）
  7. 列表接口能按 sessionId 过滤到这次运行
  8. 不存在的 traceId → 404（不返回空对象假装正常）
"""
import json
import os
import sys
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'
BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))

# 本机 HTTP_PROXY 会把「连接被拒」伪装成 502，绕开代理直连
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

fails = []
checks = []


def get_json(path):
    with _opener.open(HOST + path, timeout=15) as resp:
        return json.loads(resp.read().decode('utf-8'))


def post_json(path, payload):
    req = urllib.request.Request(HOST + path,
                                data=json.dumps(payload).encode('utf-8'),
                                headers={'Content-Type': 'application/json'})
    with _opener.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode('utf-8'))


def check(name, ok, detail=''):
    checks.append((name, ok, detail))
    if not ok:
        fails.append('%s：%s' % (name, detail))
    print('  [%s] %-46s %s' % ('PASS' if ok else 'FAIL', name, detail))


def expect_items():
    """读评测集：返回 (单日题, 窗口题)。单日题验埋点完整性，窗口题验 final_sql 记的是半开区间 SQL。"""
    with open(os.path.join(BASE, 'qa', 'eval_expectations.json'), encoding='utf-8') as fp:
        doc = json.load(fp)
    items = doc['items']
    window_nos = {2, 11, 12, 13, 14}
    single = next(i for i in items if i['no'] not in window_nos)
    window = next(i for i in items if i['no'] in window_nos)
    return single, window


def run_once(question, session):
    """打一次问数并取回 trace 明细；服务端没给 traceId 直接判失败"""
    ask = post_json('/api/ask', {'question': question, 'sessionId': session})
    trace_id = ask.get('traceId')
    if not trace_id:
        return None, None
    return trace_id, get_json('/api/traces/%s' % trace_id)


def print_trace(trace_id, run, steps):
    print('  traceId  %s' % trace_id)
    print('  run      status=%s latency=%sms tokens=%s cost=%s'
          % (run.get('status'), run.get('latencyMs'), run.get('tokens'), run.get('cost')))
    print('  %-3s %-14s %-10s %-8s %-8s %s' % ('#', 'step', 'type', 'status', 'ms', 'output'))
    for s in steps:
        print('  %-3d %-14s %-10s %-8s %-8s %s' % (
            s['stepOrder'], s['stepName'], s['spanType'], s['status'],
            s['latencyMs'], (s.get('output') or '')[:44]))


def assert_trace(label, trace_id, detail, baseline):
    """一次 trace 的通用断言（记录完整性 + 三类 span + 成本），返回 steps 供调用方追加断言"""
    run, steps = detail['run'], detail['steps']
    print('\n== %s ==' % label)
    print_trace(trace_id, run, steps)

    check('%s：run.traceId 一致' % label, run.get('traceId') == trace_id, str(run.get('traceId')))
    check('%s：status = SUCCESS' % label, run.get('status') == 'SUCCESS', str(run.get('status')))
    check('%s：latencyMs > 0' % label, (run.get('latencyMs') or 0) > 0, '%sms' % run.get('latencyMs'))
    check('%s：tokens > 0' % label, (run.get('tokens') or 0) > 0, '%s' % run.get('tokens'))
    check('%s：cost > 0（成本真算，不是 0）' % label, (run.get('cost') or 0) > 0,
          '%.6f 元' % (run.get('cost') or 0))
    check('%s：step 数 ≥ 当前装配基线' % label, len(steps) >= baseline,
          '%d / 基线 %d' % (len(steps), baseline))
    orders = [s['stepOrder'] for s in steps]
    check('%s：stepOrder 从 1 起严格递增' % label, orders == list(range(1, len(steps) + 1)), str(orders))

    types = [s['spanType'] for s in steps]
    for t in ('llm', 'sql', 'gate'):
        check('%s：含 span_type=%s' % (label, t), t in types, '共 %d 条' % types.count(t))
    llm_steps = [s for s in steps if s['spanType'] == 'llm']
    check('%s：llm span 的 tokenUsed > 0' % label,
          bool(llm_steps) and llm_steps[0]['tokenUsed'] > 0,
          '%s' % (llm_steps[0]['tokenUsed'] if llm_steps else 'n/a'))
    check('%s：span 里没有 FAILED' % label, all(s['status'] == 'SUCCESS' for s in steps),
          ','.join(s['stepName'] for s in steps if s['status'] != 'SUCCESS') or '无')
    check('%s：run.sql 非空' % label, bool(run.get('sql')), (run.get('sql') or '')[:50])
    return run, steps


def main():
    # ① 埋点目录基线（不写死数字）
    try:
        health = get_json('/api/health/traces')
    except Exception as e:
        print('SKIP: 无法连接 %s（%s）' % (HOST, e))
        print('      先启动服务：mvn -o spring-boot:run -Dspring-boot.run.arguments="--server.port=18080 --datapilot.llm.provider=stub"')
        return 2

    baseline = health['expectedSpansPerSuccess']
    catalog = [c['stepName'] for c in health['spanCatalog']]
    print('== 埋点目录 ==')
    print('  基线 span 数  %d' % baseline)
    print('  span 目录     %s' % ', '.join(catalog))
    print('  span 类型     %s' % ', '.join(health['spanTypes']))

    single, window = expect_items()
    session = 'e2e-traces-%d' % os.getpid()

    # ② 单日题：埋点完整性
    t1, d1 = run_once(single['question'], session)
    if not t1:
        print('\nFAIL: 响应里没有 traceId，后续断言无法进行')
        return 1
    assert_trace('Q%s 单日题' % single['no'], t1, d1, baseline)

    # ③ 窗口题：final_sql 必须记的是半开区间（不能只在 SQL 层对，落库的又是另一条）
    t2, d2 = run_once(window['question'], session)
    if not t2:
        print('\nFAIL: 窗口题响应里没有 traceId')
        return 1
    run2, _ = assert_trace('Q%s 窗口题' % window['no'], t2, d2, baseline)
    sql2 = run2.get('sql') or ''
    check('窗口题：final_sql 记的是半开区间', 'dt > ?' in sql2 and 'dt <= ?' in sql2, sql2[:70])
    check('窗口题：run.status 仍为 SUCCESS', run2.get('status') == 'SUCCESS', str(run2.get('status')))

    # ④ 两次运行互不污染
    check('两次运行 traceId 不同', t1 != t2, '%s vs %s' % (t1[:8], t2[:8]))
    listed = get_json('/api/traces?limit=20&sessionId=%s' % session)
    ids = [i['traceId'] for i in listed['items']]
    check('列表接口按 sessionId 命中两次运行', t1 in ids and t2 in ids, '%d 条' % listed['total'])

    missing_code = 0
    try:
        get_json('/api/traces/not-exist-%d' % os.getpid())
    except urllib.error.HTTPError as e:
        missing_code = e.code
    check('不存在的 traceId → 404', missing_code == 404, '实际 %s' % missing_code)

    print('\n== 结论 ==')
    if fails:
        print('  FAIL %d 条：' % len(fails))
        for f in fails:
            print('   - %s' % f)
        return 1
    print('  PASS（%d 项全绿：2 次运行 / 基线 %d）' % (len(checks), baseline))
    return 0


if __name__ == '__main__':
    sys.exit(main())

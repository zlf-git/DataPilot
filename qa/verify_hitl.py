# -*- coding: utf-8 -*-
"""
Ticket 12 端到端验收：HITL（预览 / 执行两段）。

判据全部来自外部行为（HTTP 响应体 + trace 明细），不看内部状态：
  1. preview 只校验不执行：有 sql/params/四道闸门 PASS，但 rows 为空，且 trace 里没有 execute 步骤
  2. preview 的 run 状态是 PREVIEW（不是 SUCCESS），hitl_preview 的 status=PREVIEW、span_type=hitl
  3. preview 不污染多轮上下文：预览过但没执行，下一句「那环比呢」仍判无法识别
  4. execute（不带编辑）出的数与直接 /api/ask 完全一致，且 trace 里不重新调 LLM
     （没有 rewrite/recall/prompt/llm_generate），说明「确认执行」不会偷偷换一份查询
  5. execute 重跑闸门 1~4：response.gates 四条齐全
  6. 改过滤值生效：小程序 → APP，结果从 15604.97 变成 20324.53（数真的变了，不是只回显）
  7. 改 value 不产生注入：值里有引号与 OR 1=1 时，SQL 文本保持参数化、注入串只在 params 里
  8. 改维度（白名单外字段）被闸门①当场拦下：证明闸门真的重跑了（预览时该 IR 是 PASS）
  9. 改指标 → status=REJECTED + structuralChanges 非空 + hitl span 记 REPLAN + 一条 SQL 都没发
 10. 假 previewId → 明确提示重新预览，不猜一份查询出来执行
 11. /api/health/hitl 与 /api/health/traces 的基线口径：hitl span 不算进成功问数的基线

用法：python verify_hitl.py [host]     默认 http://127.0.0.1:18080
退出码：0 通过 / 1 断言失败 / 2 服务不可达
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'

# 本机 HTTP_PROXY 会把「连接被拒」伪装成 502，绕开代理直连
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

fails = []
checks = []

# q3：1 指标 + 2 过滤（dt / channel_name），最合适做「改值」的实验
QUESTION = '10月19日小程序渠道的GMV是多少'
GMV_MINIAPP = 15604.97
GMV_APP = 20324.53


def get_json(path):
    with _opener.open(HOST + path, timeout=15) as resp:
        return json.loads(resp.read().decode('utf-8'))


def post_json(path, payload):
    req = urllib.request.Request(HOST + path,
                                data=json.dumps(payload).encode('utf-8'),
                                headers={'Content-Type': 'application/json'})
    with _opener.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode('utf-8'))


def ask(question, session_id=None):
    payload = {'question': question}
    if session_id is not None:
        payload['sessionId'] = session_id
    return post_json('/api/ask', payload)


def preview(question, session_id=None):
    payload = {'question': question}
    if session_id is not None:
        payload['sessionId'] = session_id
    return post_json('/api/ask/preview', payload)


def execute(preview_id, ir=None):
    payload = {'previewId': preview_id}
    if ir is not None:
        payload['ir'] = ir
    return post_json('/api/ask/execute', payload)


def steps_of(trace_id):
    detail = get_json('/api/traces/' + trace_id)
    return detail['steps'], detail['run']


def names(steps):
    return [s['stepName'] for s in steps]


def val(r):
    rows = r.get('rows') or []
    if len(rows) == 1 and len(rows[0]) == 1:
        return list(rows[0].values())[0]
    return None


def check(name, ok, detail=''):
    checks.append((name, ok, detail))
    if not ok:
        fails.append('%s：%s' % (name, detail))
    print('  [%s] %-46s %s' % ('PASS' if ok else 'FAIL', name, detail))


def main():
    print('== 起点：同一句直接问（作为对照）==')
    direct = ask(QUESTION)
    check('对照组直接问出数', val(direct) == GMV_MINIAPP and direct.get('status') == 'SUCCESS',
          'status=%s value=%s' % (direct.get('status'), val(direct)))
    direct_sql = direct.get('sql') or ''
    check('对照组 SQL 是参数化 + 单日过滤', '?' in direct_sql and '小程序' not in direct_sql, direct_sql)

    print('== ① 预览：只校验不执行 ==')
    sid = 'hitl-' + uuid.uuid4().hex[:8]
    pv = preview(QUESTION, sid)
    pid = pv.get('previewId') or ''
    check('preview 返回 previewId', len(pid) == 32, pid)
    check('preview 状态是 PREVIEW', pv.get('status') == 'PREVIEW', str(pv.get('status')))
    check('preview 生成了 SQL', bool(pv.get('sql')), (pv.get('sql') or '')[:60])
    check('preview 带参数', bool(pv.get('params')), str(pv.get('params')))
    check('preview 一行数据都没有（真的没执行）', (pv.get('rows') or []) == [], str(pv.get('rows')))
    check('preview 四道闸门全 PASS',
          len(pv.get('gates') or []) == 4 and all('REJECT' not in g for g in pv.get('gates') or []),
          str(pv.get('gates')))
    check('preview 的 SQL 与直接问的一致（同一份 IR → 同一段代码）',
          (pv.get('sql') or '') == direct_sql, (pv.get('sql') or '')[:60])

    steps, run = steps_of(pv['traceId'])
    check('preview 的 trace 里没有 execute 步骤（一条 SQL 都没发）',
          'execute' not in names(steps), str(names(steps)))
    check('preview 的 run 状态是 PREVIEW', run.get('status') == 'PREVIEW', str(run.get('status')))
    hitl = [s for s in steps if s['stepName'] == 'hitl_preview']
    check('preview 落了 hitl_preview 且 span_type=hitl', len(hitl) == 1 and hitl[0]['spanType'] == 'hitl',
          str([(s['stepName'], s['spanType']) for s in steps]))
    check('hitl_preview 的 status=PREVIEW（PREVIEW 是状态不是 span_type）',
          hitl and hitl[0]['status'] == 'PREVIEW', hitl[0]['status'] if hitl else 'missing')
    check('preview 的 final_sql 已归档（预览的 SQL 也要留痕）',
          (run.get('sql') or '') == direct_sql, (run.get('sql') or '')[:50])

    print('== ② 预览不污染多轮上下文 ==')
    lonely = ask('那环比呢', sid)
    check('预览过但没执行 → 追问仍判无法识别（预览不算一轮问答）',
          not lonely.get('sql') and '无法识别' in (lonely.get('answer') or ''),
          lonely.get('answer', ''))

    print('== ③ 确认执行：不重新调 LLM、重跑闸门 1~4 ==')
    ex = execute(pid)
    check('execute 状态 SUCCESS', ex.get('status') == 'SUCCESS', str(ex.get('status')))
    check('execute 出的数与直接问一致', val(ex) == GMV_MINIAPP,
          'value=%s' % (val(ex),))
    check('execute 回显了 previewId', (ex.get('previewId') or '') == pid, str(ex.get('previewId')))
    # execute 会走完 finish 段，所以闸门回显是 4（IR/SQL 层重跑）+ 1（结果自检）
    gates = ex.get('gates') or []
    check('execute 重跑闸门 1~4：前四条齐全且全 PASS',
          gates[:4] == ['存在性=PASS', '权限=PASS', '代价=PASS', '语法=PASS'], str(gates))
    check('execute 也跑了闸门⑤（走到了执行与结果自检）', len(gates) == 5, str(gates))
    steps, run = steps_of(ex['traceId'])
    nm = names(steps)
    check('execute 不重新生成 IR（无 rewrite/recall/prompt/llm_generate）',
          not any(n in nm for n in ('rewrite', 'recall', 'prompt', 'llm_generate')), str(nm))
    check('execute 确实重跑了 闸门①②③ / IR→SQL / 闸门④',
          all(n in nm for n in ('gate_ir', 'ir_to_sql', 'gate_sql', 'execute')), str(nm))
    hx = [s for s in steps if s['stepName'] == 'hitl_execute']
    check('execute 落了 hitl_execute 且 status=SUCCESS',
          len(hx) == 1 and hx[0]['status'] == 'SUCCESS' and hx[0]['spanType'] == 'hitl',
          str([(s['stepName'], s['status']) for s in steps]))
    check('execute 的 run 状态是 SUCCESS', run.get('status') == 'SUCCESS', str(run.get('status')))
    check('execute 的 final_sql 是这次真正执行的 SQL（不是预览那份的照抄）',
          (run.get('sql') or '') == (ex.get('sql') or ''), (run.get('sql') or '')[:50])

    print('== ④ 改过滤值：真的改了，不是只回显 ==')
    pv2 = preview(QUESTION)
    edited = pv2['ir']
    edited['filters'][1]['value'] = 'APP'
    ex2 = execute(pv2['previewId'], edited)
    check('改 value 后执行成功', ex2.get('status') == 'SUCCESS', str(ex2.get('status')))
    check('结果随编辑变化（小程序 15604.97 → APP 20324.53）', val(ex2) == GMV_APP, 'value=%s' % (val(ex2),))
    check('edits 里如实列出被接受的编辑',
          any('值变更' in e for e in ex2.get('edits') or []), str(ex2.get('edits')))
    check('没有结构变更记录', (ex2.get('structuralChanges') or []) == [], str(ex2.get('structuralChanges')))

    print('== ⑤ 改 value 不产生注入 ==')
    pv3 = preview(QUESTION)
    payload = "小程序' OR '1'='1"
    edited = pv3['ir']
    edited['filters'][1]['value'] = payload
    ex3 = execute(pv3['previewId'], edited)
    sql3 = ex3.get('sql') or ''
    check('注入串没有进 SQL 文本', "OR '1'='1" not in sql3 and '小程序' not in sql3, sql3)
    check('SQL 仍是参数化（? 占位）', sql3.count('?') >= 2, sql3)
    check('注入串原样落在 params 里（当值处理，不当语法）',
          payload in [str(x) for x in (ex3.get('params') or [])], str(ex3.get('params')))
    # SUM 无匹配行会返回一行 NULL（不是 0 行），所以判据是「值取不到」，不是「行数为 0」
    check('执行没被注入带飞（没有该渠道 → 取不到值）', val(ex3) is None, str(ex3.get('rows')))
    check('注入值没有把别的渠道的数据带出来',
          (val(ex3) is None) or val(ex3) != GMV_APP, str(val(ex3)))
    check('闸门照样全过（注入不是靠闸门拦，是靠参数化不吃值）',
          all('REJECT' not in g for g in ex3.get('gates') or []), str(ex3.get('gates')))

    print('== ⑥ 改维度：闸门①当场拦（证明闸门重跑了）==')
    pv4 = preview(QUESTION)
    edited = pv4['ir']
    edited['dimensions'] = [{'field': 'secret_col'}]
    ex4 = execute(pv4['previewId'], edited)
    check('白名单外维度 → status=REJECTED', ex4.get('status') == 'REJECTED', str(ex4.get('status')))
    check('被闸门①拦下（存在性=REJECT）',
          any('存在性=REJECT' in g for g in ex4.get('gates') or []), str(ex4.get('gates')))
    check('拒绝原因指名道姓 field', 'secret_col' in (ex4.get('answer') or ''), (ex4.get('answer') or '')[:60])
    steps, _ = steps_of(ex4['traceId'])
    check('被拦时没有 execute 步骤（不该带着非法维度去查库）',
          'execute' not in names(steps), str(names(steps)))
    check('维度变更本身是允许的编辑（拦它的是闸门，不是编辑守门）',
          any('维度变更' in e for e in ex4.get('edits') or []), str(ex4.get('edits')))

    print('== ⑦ 结构变更：REPLAN，一条 SQL 都不发 ==')
    pv5 = preview(QUESTION)
    edited = pv5['ir']
    edited['metrics'].append({'metricId': 'gmv_total'})
    ex5 = execute(pv5['previewId'], edited)
    check('改指标 → status=REJECTED', ex5.get('status') == 'REJECTED', str(ex5.get('status')))
    check('structuralChanges 非空', bool(ex5.get('structuralChanges')), str(ex5.get('structuralChanges')))
    check('提示回去重新问', '重新问' in (ex5.get('answer') or ''), (ex5.get('answer') or '')[:60])
    check('结构变更时没有 SQL', not (ex5.get('sql') or ''), str(ex5.get('sql')))
    steps, run = steps_of(ex5['traceId'])
    check('REPLAN 只落 hitl_execute 一条 span（没往下走）',
          names(steps) == ['hitl_execute'], str(names(steps)))
    check('hitl_execute 的 status=REPLAN', steps[0]['status'] == 'REPLAN', steps[0]['status'])
    check('REPLAN 的 run 状态是 REJECTED（策略拒绝，不是故障）',
          run.get('status') == 'REJECTED', str(run.get('status')))

    print('== ⑧ 假 previewId：明确拒绝，不猜 ==')
    fake = execute('0' * 32)
    check('假 previewId → status=FAILED', fake.get('status') == 'FAILED', str(fake.get('status')))
    check('提示重新预览', '重新预览' in (fake.get('answer') or ''), (fake.get('answer') or '')[:60])
    check('假 previewId 时不产出 SQL', not (fake.get('sql') or ''), str(fake.get('sql')))
    check('假 previewId 时没有 trace（没跑过就不编一条运行记录）',
          fake.get('traceId') is None, str(fake.get('traceId')))

    print('== ⑨ 多轮环比：预览 / 执行与一步到底的行为一致 ==')
    sid2 = 'hitl-cmp-' + uuid.uuid4().hex[:6]
    base_ask = ask('近7天累计支付订单数是多少', sid2)
    check('先建一轮上下文（本期能出数）', bool(base_ask.get('sql')), (base_ask.get('sql') or '')[:40])
    pv6 = preview('那环比呢', sid2)
    check('环比问题也能预览', (pv6.get('previewId') or '') != '' and pv6.get('status') == 'PREVIEW',
          '%s %s' % (pv6.get('status'), (pv6.get('rewrittenQuestion') or '')[:30]))
    check('预览回显补全后的问题（含环比）', '环比' in (pv6.get('rewrittenQuestion') or ''),
          (pv6.get('rewrittenQuestion') or '')[:40])
    ex6 = execute(pv6['previewId'])
    check('执行后仍带环比结论（不是只出本期）',
          '环比' in (ex6.get('answer') or ''), (ex6.get('answer') or '')[:50])
    check('执行后给出环比 SQL（半开区间）',
          'dt > ?' in (ex6.get('comparisonSql') or '') and 'dt <= ?' in (ex6.get('comparisonSql') or ''),
          (ex6.get('comparisonSql') or '')[:50])

    print('== ⑩ 落库口径与基线 ==')
    health = get_json('/api/health/hitl')
    check('previews >= 1', health.get('previews', 0) >= 1, str(health.get('previews')))
    check('存储口径是内存', health.get('storage') == 'memory', str(health.get('storage')))
    check('可编辑面收敛为四项',
          sorted(health.get('editable') or []) == sorted(['filters[].value', 'limit',
                                                          'orderBy[].direction', 'dimensions']),
          str(health.get('editable')))
    traces = get_json('/api/health/traces')
    check('hitl span 单独列出', sorted(traces.get('hitlSpans') or []) == ['hitl_execute', 'hitl_preview'],
          str(traces.get('hitlSpans')))
    ask_steps, _ = steps_of(direct['traceId'])
    check('普通问数的 span 数仍等于 ask 基线（hitl 没混进基线）',
          len(ask_steps) == traces.get('expectedSpansPerSuccess'),
          '%d vs %s' % (len(ask_steps), traces.get('expectedSpansPerSuccess')))
    check('ask 基线里没有 hitl span',
          not any(s['spanType'] == 'hitl' for s in ask_steps),
          str([s['spanType'] for s in ask_steps]))

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

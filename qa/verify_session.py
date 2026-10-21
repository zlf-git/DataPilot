# -*- coding: utf-8 -*-
"""
Ticket 10 端到端验收：多轮 session。

判据全部来自外部行为（HTTP 响应体），不看内部状态：
  1. 第一问（近 7 天窗口）能出数
  2. 同一 sessionId 追问「那环比呢」→ rewrittenQuestion 补全成「上一轮问题 + 环比：与前 7 天对比」
  3. 环比那次查询的 SQL 仍是半开区间（dt > ? AND dt <= ?），且与本期 SQL 同模板
  4. 环比结论如实为「前 7 天无数据」（库内只有 2026-10-13~10-19 七天）
  5. 不带 sessionId 的同一句追问 → 无法识别（没上下文就不补，不猜）
  6. 换了 sessionId 的同一句追问 → 无法识别（会话之间不串）
  7. 同比 → 明确拒绝并说明「没有去年同期」
  8. 带新维度的追问（「那渠道呢」）→ 明确拒绝，不静默答上一轮
  9. GET /api/health/session 暴露会话上限（sessions/storage=memory）

用法：python verify_session.py [host]     默认 http://127.0.0.1:18080
退出码：0 通过 / 1 断言失败 / 2 服务不可达
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'

# 本机 HTTP_PROXY 会把「连接被拒」伪装成 502，绕开代理直连
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

fails = []
checks = []

# 第一问选「近 7 天」题：环比才有窗口可挪（单日题 dt = ? 没有相对窗口）
FIRST_QUESTION = '近7天累计支付订单数是多少'


def get_json(path):
    with _opener.open(HOST + path, timeout=15) as resp:
        return json.loads(resp.read().decode('utf-8'))


def ask(question, session_id=None):
    payload = {'question': question}
    if session_id is not None:
        payload['sessionId'] = session_id
    req = urllib.request.Request(HOST + '/api/ask',
                                data=json.dumps(payload).encode('utf-8'),
                                headers={'Content-Type': 'application/json'})
    with _opener.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode('utf-8'))


def check(name, ok, detail=''):
    checks.append((name, ok, detail))
    if not ok:
        fails.append('%s：%s' % (name, detail))
    print('  [%s] %-44s %s' % ('PASS' if ok else 'FAIL', name, detail))


def val(r):
    """1×1 结果取值；取不到返回 None"""
    rows = r.get('rows') or []
    if len(rows) == 1 and len(rows[0]) == 1:
        return list(rows[0].values())[0]
    return None


def main():
    sid = 'sess-' + uuid.uuid4().hex[:8]

    print('== 第一问（近 7 天，带 sessionId）==')
    first = ask(FIRST_QUESTION, sid)
    check('第一问出数', bool(first.get('sql')) and bool(first.get('rows')),
          'value=%s sql=%s' % (val(first), (first.get('sql') or '')[:60]))
    check('第一问无错误文案', not first.get('answer', '').startswith(('无法识别', '模型服务', '问题为空')),
          first.get('answer', '')[:40])
    first_sql = first.get('sql') or ''
    check('第一问是半开区间窗口', 'dt > ?' in first_sql and 'dt <= ?' in first_sql, first_sql)

    print('== 追问「那环比呢」（同一 sessionId）==')
    second = ask('那环比呢', sid)
    rewritten = second.get('rewrittenQuestion') or ''
    check('补全结果 = 上一轮问题 + 环比说明',
          rewritten.startswith(FIRST_QUESTION) and '环比' in rewritten and '前 7 天' in rewritten,
          rewritten)
    check('本期结果与第一问一致（环比不改本期语义）',
          (second.get('sql') or '') == first_sql and val(second) == val(first),
          'value=%s' % (val(second),))
    comp_sql = second.get('comparisonSql') or ''
    check('环比 SQL 仍是半开区间', 'dt > ?' in comp_sql and 'dt <= ?' in comp_sql, comp_sql)
    check('环比 SQL 与本期同模板（只有窗口不同）',
          comp_sql == first_sql, comp_sql)
    note = second.get('comparisonNote') or ''
    check('环比结论：前 7 天无数据', '前 7 天无数据' in note, note)
    check('answer 同时给出本期与环比',
          '环比' in (second.get('answer') or '') and '前 7 天无数据' in (second.get('answer') or ''),
          second.get('answer', ''))

    print('== 无上下文 / 跨会话：不补全、不串 ==')
    lone = ask('那环比呢')
    check('无 sessionId 追问 → 无法识别',
          not lone.get('sql') and '无法识别' in (lone.get('answer') or ''),
          lone.get('answer', ''))
    other = ask('那环比呢', 'sess-other-' + uuid.uuid4().hex[:6])
    check('换 sessionId 追问 → 无法识别（不串会话）',
          not other.get('sql') and '无法识别' in (other.get('answer') or ''),
          other.get('answer', ''))

    print('== 明确拒绝的两种追问 ==')
    yoy = ask('那同比呢', sid)
    check('同比 → 说明没有去年同期',
          not yoy.get('sql') and '同比' in (yoy.get('answer') or '') and '去年同期' in (yoy.get('answer') or ''),
          yoy.get('answer', ''))
    dim = ask('那渠道呢', sid)
    check('换维度追问 → 提示把条件说全',
          not dim.get('sql') and '多轮追问暂只支持环比' in (dim.get('answer') or ''),
          dim.get('answer', ''))

    print('== 会话自检接口 ==')
    health = get_json('/api/health/session')
    check('sessions >= 1', health.get('sessions', 0) >= 1, str(health.get('sessions')))
    check('存储口径是内存', health.get('storage') == 'memory', str(health.get('storage')))
    check('上限字段齐全',
          all(k in health for k in ('maxSessions', 'maxRounds', 'ttlMinutes')),
          json.dumps(health, ensure_ascii=False))

    print()
    if fails:
        print('FAIL %d/%d：' % (len(fails), len(checks)))
        for f in fails:
            print('  - ' + f)
        return 1
    print('PASS：%d/%d 项全绿（会话 %s）' % (len(checks), len(checks), sid))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except urllib.error.URLError as e:
        print('服务不可达 %s：%s' % (HOST, e))
        sys.exit(2)

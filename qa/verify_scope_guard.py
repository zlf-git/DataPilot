# -*- coding: utf-8 -*-
"""
Ticket 17 端到端验收：C5② 跨期不可加指标的「显式拒绝」（收口 QA-16 P2-1）。

背景：Ticket 16 在 seed.sql 与 Ontology §6.5 都写了「近 N 天累计支付用户 → 应显式拒绝」，
但代码里没有对应分支，实测返回的是兜底那句「无法识别查询意图，请换个问法」。
数没错（没给偏大的 SUM），但解释是误导的：用户被告知「换个问法」，下一轮换个说法
很可能正好命中 SUM(paid_user_cnt)，那就是静默错数。

要证的不是「拦得住」，是「拦得准且说得清」：
  1. 「近7天累计支付用户数」→ REJECTED，文案说明是「当天去重人数 / 无法重算 COUNT(DISTINCT)」，
     并且**不是**那句不解释的「无法识别查询意图」
  2. 文案给得出可执行的替代问法（按天看 / 换成可累加的指标）
  3. 被拒时 sql 为空、rows 为空（不是先查再拒）
  4. 别名同样拦（付费用户数 / 成交用户数，来自 metric_def 的 aliases）
  5. 单日问法放行：10月19日的支付用户数（当天去重，合法）
  6. 按天问法放行：近7天每天的支付用户数（每天各自去重，合法）
  7. 「总」不当跨期词：支付用户数占总订单数的比例 不被口径守卫拦
  8. 可加指标不受影响：近7天累计支付订单数、当天总GMV（数原样 53042.41）

放行类的判据是「不含口径守卫的文案」，而不是「SUCCESS」—— 守卫是纯前置判定，
放行之后能不能答出来由召回/LLM/闸门决定，不该混在一起考。

用法：python verify_scope_guard.py [host]     默认 http://127.0.0.1:18097
退出码：0 通过 / 1 断言失败 / 2 服务不可达
"""
import json
import sys
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18097'

# 本机 HTTP_PROXY 会把「连接被拒」伪装成 502，绕开代理直连
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# 守卫文案的指纹（RewriteStep#unsupportedScope）：出现它 = 被口径守卫拦下
SCOPE_MARK = '当天去重人数'
# 兜底那句（AgentEngine identify）：出现它 = 没被守卫拦，落到了「没听懂」
FALLBACK_MARK = '无法识别查询意图'

fails = []
checks = []


def check(name, ok, detail=''):
    checks.append((name, ok, detail))
    print(('  [PASS] ' if ok else '  [FAIL] ') + name + (('  ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def ask(question):
    data = json.dumps({'question': question}).encode('utf-8')
    req = urllib.request.Request(HOST + '/api/ask', data=data, method='POST')
    req.add_header('Content-Type', 'application/json')
    with _opener.open(req, timeout=90) as resp:
        return json.loads(resp.read().decode('utf-8'))


def blocked(question):
    """问一句，返回 (是否被口径守卫拦下, 完整响应)"""
    body = ask(question)
    return SCOPE_MARK in (body.get('answer') or ''), body


# ---------------- 0. 服务可达 ----------------
try:
    with _opener.open(HOST + '/api/health/metadata', timeout=10) as r:
        health = json.loads(r.read().decode('utf-8'))
except (urllib.error.URLError, ConnectionError) as e:
    print('服务不可达：%s（%s）' % (HOST, e))
    sys.exit(2)
print('后端在线：写入 %s（急停态不影响本脚本，问数只读）' % ('可用' if health.get('writeEnabled') else '急停中'))

print('== ① C5② 原句：近7天累计支付用户数 ==')
hit, body = blocked('近7天累计支付用户数是多少')
check('被口径守卫拦下（不是落到「无法识别」）', hit, (body.get('answer') or '')[:80])
check('status=REJECTED', body.get('status') == 'REJECTED', body.get('status'))
check('文案写明「当天去重人数」', '当天去重人数' in (body.get('answer') or ''), '')
check('文案写明为什么不能 SUM（COUNT(DISTINCT) / 用户明细）',
      'COUNT(DISTINCT' in (body.get('answer') or ''), (body.get('answer') or '')[:160])
check('文案不再让用户「换个问法」了事', FALLBACK_MARK not in (body.get('answer') or ''), '')
check('文案给出可执行的替代问法（按天 / 可累加指标）',
      '每天的支付用户数' in (body.get('answer') or '') and '支付订单数' in (body.get('answer') or ''), '')
check('被拦时没发 SQL、没有结果', not body.get('sql') and not body.get('rows'),
      'sql=%r rows=%s' % (body.get('sql'), body.get('rows')))

print('== ② 别名同样拦（metric_def aliases：付费用户数 / 成交用户数）==')
for q in ('本月付费用户数是多少', '近30天合计成交用户数', '最近7天付费人数'):
    hit, body = blocked(q)
    check('「%s」被拦' % q, hit, (body.get('answer') or '')[:60])

print('== ③ 单日问法放行：10月19日的支付用户数 ==')
hit, body = blocked('10月19日的支付用户数是多少')
check('未被口径守卫拦（单日去重合法）', not hit, (body.get('answer') or '')[:80])

print('== ④ 按天问法放行：近7天每天的支付用户数 ==')
for q in ('近7天每天的支付用户数是多少', '近7天每日支付用户数走势', '近7天支付用户数的按天趋势'):
    hit, body = blocked(q)
    check('「%s」未被拦（按天各自去重）' % q, not hit, (body.get('answer') or '')[:60])

print('== ⑤ 「总」不当跨期词：支付用户数占总订单数的比例 ==')
hit, body = blocked('支付用户数占总订单数的比例是多少')
check('未被口径守卫拦（占比不是求和）', not hit, (body.get('answer') or '')[:80])

print('== ⑥ 可加指标不受影响（回归）==')
hit, body = blocked('近7天累计支付订单数是多少')
check('「近7天累计支付订单数」未被拦（订单数可累加）', not hit, (body.get('answer') or '')[:60])

gmv = ask('10月19日当天的总GMV是多少')
check('当天总GMV 仍是 SUCCESS', gmv.get('status') == 'SUCCESS', gmv.get('status'))
check('GMV 数原样 53042.41（守卫没碰主链路）',
      bool(gmv.get('rows')) and '53042.41' in json.dumps(gmv.get('rows'), ensure_ascii=False),
      json.dumps(gmv.get('rows'), ensure_ascii=False)[:80])

print('')
if fails:
    print('FAIL：%d/%d 项未通过 → %s' % (len(fails), len(checks), '；'.join(fails)))
    sys.exit(1)
print('PASS：%d/%d 项全绿' % (len(checks), len(checks)))

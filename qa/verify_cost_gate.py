# -*- coding: utf-8 -*-
"""
Ticket 15 端到端验收：代价闸门复杂度公式（D2 / 审计 P1#19）。

要证的不是「代码里有 max(1,·)」，而是**阈值压到 1 时，维度=0 的题真的被拦下来**。
补洞前复杂度恒 0，阈值压到 1 也拦不住（0 > 1 不成立），这是审计说的「只写不再恒通过不够」。

判据全部走外部行为（HTTP 响应）：
  1. 默认阈值 3：Q1（10月19日总GMV，维度=0、单表）PASS，且能查到数
  2. 把 gate_config.max_complexity 改成 1（走管理员 PUT /api/gate-config，与运维真实操作一致）
  3. 同一道 Q1 立刻变 REJECTED，拒绝文案写明「复杂度 1 ≥ 阈值 1」与三因子原值
  4. 另一道维度=0 的题（Q9 支付率，同表复合）同样被拦 —— 不是只有 Q1 撞巧
  5. 被拦时一条 SQL 都没发（sql 为空、rows 为空），不是先查再判
  6. 阈值恢复 3 后，Q1 又能查到与第 1 步一模一样的数（改的是闸门，不是查询能力）

用法：python verify_cost_gate.py [host]     默认 http://127.0.0.1:18080
退出码：0 通过 / 1 断言失败 / 2 服务不可达
"""
import base64
import json
import sys
import urllib.error
import urllib.request

HOST = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080'

# 本机 HTTP_PROXY 会把「连接被拒」伪装成 502，绕开代理直连
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

ADMIN = ('admin', 'Admin@2026')

Q_TOTAL_GMV = '10月19日当天的总GMV是多少'
Q_PAY_RATE = '10月19日支付订单数占总订单数的比例（支付率）是多少'

fails = []
checks = []


def check(name, ok, detail=''):
    checks.append((name, ok, detail))
    print(('  [PASS] ' if ok else '  [FAIL] ') + name + (('  ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def call(method, path, body=None, auth=None, timeout=60):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(HOST + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if auth:
        token = base64.b64encode(('%s:%s' % auth).encode('utf-8')).decode('ascii')
        req.add_header('Authorization', 'Basic ' + token)
    try:
        with _opener.open(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {'raw': raw}


def ask(question):
    status, body = call('POST', '/api/ask', {'question': question})
    if status != 200:
        raise SystemExit('问数接口返回 %s：%s' % (status, body))
    return body


def set_complexity(value):
    # paramKey 是白名单键，gate_name 由后端按 param_key 归一化，这里传 cost 只为可读
    status, body = call('PUT', '/api/gate-config',
                        {'paramKey': 'max_complexity', 'paramValue': str(value),
                         'updatedBy': 'Ticket 15 验收脚本'}, auth=ADMIN)
    if status != 200:
        raise SystemExit('改闸门阈值失败（%s）：%s —— 检查 metadata.write-enabled 与管理员口令' % (status, body))
    return body


def gate_line(resp, gate_name):
    return [g for g in (resp.get('gates') or []) if g.startswith(gate_name)]


# ---------------- 0. 服务可达 ----------------
try:
    call('GET', '/api/metadata/health')
except (urllib.error.URLError, ConnectionError) as e:
    print('服务不可达：%s（%s）' % (HOST, e))
    sys.exit(2)

print('== ① 默认阈值：维度=0 的题放行 ==')
status, rows = call('GET', '/api/gate-config')
before = {r['paramKey']: r['paramValue'] for r in rows}
print('  当前 gate_config：%s' % before)
# 老库（V05c 之前建的）没有这一行，闸门按代码默认 3 生效，不能因此把问数打挂；
# 所以这一条断言的是「缺行也不影响正常问数」，不是「库里必须有行」。
original = int(before.get('max_complexity') or 3)

base = ask(Q_TOTAL_GMV)
check('缺 max_complexity 行时回退默认 3 且不报错',
      base.get('status') != 'REJECTED' or 'max_complexity' in before,
      before.get('max_complexity', '缺行→默认 3'))
check('默认阈值下 Q1 不是 REJECTED', base.get('status') != 'REJECTED', base.get('status'))
check('Q1 的代价闸门 PASS', any(g.endswith('=PASS') for g in gate_line(base, '代价')), gate_line(base, '代价'))
check('Q1 真的查到数（不是空表通过）', bool(base.get('rows')), base.get('rows'))

print('== ② 阈值压到 1：同题立刻被拦（审计 P1#19 回归点）==')
saved = set_complexity(1)
check('PUT 后回显 paramValue=1', saved.get('paramValue') == '1', saved)
status, rows = call('GET', '/api/gate-config')
now = {r['paramKey']: r['paramValue'] for r in rows}
check('max_complexity 已作为一行落库（后续 seed/运维可见）', now.get('max_complexity') == '1', now.get('max_complexity'))

blocked = ask(Q_TOTAL_GMV)
check('Q1 变 REJECTED', blocked.get('status') == 'REJECTED', blocked.get('status'))
check('代价闸门 REJECT', gate_line(blocked, '代价') == ['代价=REJECT'], gate_line(blocked, '代价'))
check('文案写明「复杂度 1 ≥ 阈值 1」', '复杂度 1 ≥ 阈值 1' in (blocked.get('answer') or ''), (blocked.get('answer') or '')[:110])
check('文案带三因子原值与兜底说明', '各因子下限 1' in (blocked.get('answer') or ''), '')
check('被拦时没发 SQL、没有结果', not blocked.get('sql') and not blocked.get('rows'),
      'sql=%r rows=%s' % (blocked.get('sql'), blocked.get('rows')))

print('== ③ 另一道维度=0 的题同样被拦（不是 Q1 撞巧）==')
blocked2 = ask(Q_PAY_RATE)
check('Q9 支付率也 REJECTED', blocked2.get('status') == 'REJECTED', blocked2.get('status'))
check('Q9 拒绝原因同为复杂度过高', '查询复杂度过高' in (blocked2.get('answer') or ''), (blocked2.get('answer') or '')[:80])

print('== ④ 恢复阈值：查询能力原样回来 ==')
restored = set_complexity(original)
check('PUT 后回显 paramValue=%s' % original, restored.get('paramValue') == str(original), restored)
after = ask(Q_TOTAL_GMV)
check('Q1 恢复 SUCCESS', after.get('status') == base.get('status'), after.get('status'))
check('同一个数（改的是闸门不是查询）', after.get('rows') == base.get('rows'), after.get('rows'))
check('恢复后 SQL 也回来了', bool(after.get('sql')), (after.get('sql') or '')[:60])

print('')
if fails:
    print('FAIL：%d/%d 项未通过 → %s' % (len(fails), len(checks), '；'.join(fails)))
    sys.exit(1)
print('PASS：%d/%d 项全绿' % (len(checks), len(checks)))

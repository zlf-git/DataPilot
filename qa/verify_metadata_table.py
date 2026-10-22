#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
表注册 / 注销接口验收（Ticket 19）。

背景：评审在「加表零改动」那条链路上找到的真正缺口之一，是元数据**只有读接口**——
加一张表只能手工 `INSERT INTO semantic_model`，绕过 write-enabled 急停与审计。
本脚本验的正是补上之后的完整链路：

    注册表 → 加字段 → 删表被字段挡住 → 摘字段 → 删表成功 → 清单里消失

外加三条边界：
    * 重复 create → 409（不覆盖已有登记）
    * upsert 能改注释
    * 删不存在的表 → 404（不是 400）

以及缺口 1 的回归点：**加一张新表之后 `/api/health/engine` 不能报 consistent=false**
（那正是「引擎声明写死 4 张表」造成的误报）。

用法：python qa/verify_metadata_table.py [host]
默认 host = http://127.0.0.1:18080；写接口需要 admin 凭证（Basic）。
"""
import base64
import json
import sys
import urllib.error
import urllib.request

HOST = (sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18080').rstrip('/')
ADMIN = ('admin', 'Admin@2026')
TABLE = 'zz_qa_table_ticket19'
FIELD = 'zz_qa_col'

pass_n = 0
fail_n = 0
failures = []


def check(name, ok, detail=''):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print('  [PASS] %s' % name)
    else:
        fail_n += 1
        failures.append(name)
        print('  [FAIL] %s%s' % (name, (' → %s' % detail) if detail else ''))


def auth_header(user=None):
    if user is None:
        return {}
    token = base64.b64encode(('%s:%s' % user).encode('utf-8')).decode('ascii')
    return {'Authorization': 'Basic ' + token}


def call(method, path, body=None, user=None):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(HOST + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    for k, v in auth_header(user).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode('utf-8')
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return e.code, json.loads(raw) if raw.strip() else None
        except json.JSONDecodeError:
            return e.code, raw


def table_names():
    _, rows = call('GET', '/api/metadata/tables')
    return [r.get('tableName') for r in (rows or [])]


def field_names(table):
    _, rows = call('GET', '/api/metadata/fields')
    return [r.get('fieldName') for r in (rows or []) if r.get('tableName') == table]


def cleanup():
    """无论断言怎么失败，都把本次造的数据清干净，不给下一轮留残留"""
    for f in field_names(TABLE):
        call('DELETE', '/api/metadata/fields?tableName=%s&fieldName=%s' % (TABLE, f), user=ADMIN)
    if TABLE in table_names():
        call('DELETE', '/api/metadata/tables?tableName=%s' % TABLE, user=ADMIN)


# ---------------- 0. 服务可达 ----------------
code, health = call('GET', '/api/health/metadata')
if code != 200:
    print('服务不可达：%s → HTTP %s' % (HOST, code))
    sys.exit(2)
print('后端在线：%s' % json.dumps(health, ensure_ascii=False))

cleanup()

print('== ① 加表前：4 张 ADS 表在册 ==')
before = table_names()
check('表清单含 4 张 ADS 表', len([t for t in before if t.startswith('ads_')]) == 4, before)

print('== ② 注册一张新表（mode=create）==')
code, created = call('PUT', '/api/metadata/tables?mode=create',
                     {'tableName': TABLE, 'tableComment': 'QA 临时表', 'bizDomain': '测试'}, user=ADMIN)
check('PUT 返回 200', code == 200, 'HTTP %s %s' % (code, created))
check('回显 tableName', (created or {}).get('tableName') == TABLE, created)
check('新表出现在清单里（缓存已失效，不用等 60s TTL）', TABLE in table_names(), table_names())

print('== ③ 重复 create → 409（不覆盖已有登记）==')
code, dup = call('PUT', '/api/metadata/tables?mode=create',
                 {'tableName': TABLE, 'tableComment': '想偷偷改注释'}, user=ADMIN)
check('重复 create 返回 409', code == 409, 'HTTP %s' % code)

print('== ④ upsert 能改注释 ==')
code, upd = call('PUT', '/api/metadata/tables',
                 {'tableName': TABLE, 'tableComment': 'QA 临时表（改过）', 'bizDomain': '测试'}, user=ADMIN)
check('upsert 返回 200', code == 200, 'HTTP %s' % code)
_, rows = call('GET', '/api/metadata/tables')
got = [r for r in (rows or []) if r.get('tableName') == TABLE]
check('注释真的落库了', bool(got) and got[0].get('tableComment') == 'QA 临时表（改过）', got)

print('== ⑤ 加表之后可以加字段（加表零改动的下一环）==')
code, fld = call('PUT', '/api/metadata/fields',
                 {'tableName': TABLE, 'fieldName': FIELD, 'fieldType': 'varchar',
                  'fieldComment': 'QA 临时字段', 'fieldRole': 'dimension'}, user=ADMIN)
check('给新表加字段 200', code == 200, 'HTTP %s %s' % (code, fld))
check('字段出现在白名单里', FIELD in field_names(TABLE), field_names(TABLE))

print('== ⑥ 表里还有字段时不许删表（不级联）==')
code, blocked = call('DELETE', '/api/metadata/tables?tableName=%s' % TABLE, user=ADMIN)
check('删表被拒（400）', code == 400, 'HTTP %s' % code)
msg = json.dumps(blocked, ensure_ascii=False) if blocked else ''
check('原因写明「还有 N 个字段」，让人知道下一步做什么', '字段' in msg, msg[:160])
check('表仍在册（没有被级联删掉）', TABLE in table_names(), '')

print('== ⑦ 摘掉字段后删表成功 ==')
code, _ = call('DELETE', '/api/metadata/fields?tableName=%s&fieldName=%s' % (TABLE, FIELD), user=ADMIN)
check('删字段 200', code == 200, 'HTTP %s' % code)
code, deleted = call('DELETE', '/api/metadata/tables?tableName=%s' % TABLE, user=ADMIN)
check('删表 200', code == 200, 'HTTP %s %s' % (code, deleted))
check('清单里已消失', TABLE not in table_names(), table_names())

print('== ⑧ 删不存在的表 → 404（不是 400）==')
code, missing = call('DELETE', '/api/metadata/tables?tableName=%s' % TABLE, user=ADMIN)
check('再删一次返回 404', code == 404, 'HTTP %s %s' % (code, missing))

print('== ⑨ 鉴权与急停（写接口不能裸奔）==')
code, anon = call('PUT', '/api/metadata/tables?mode=create',
                  {'tableName': 'zz_anon_probe', 'tableComment': '匿名'})
check('匿名注册表 → 401', code == 401, 'HTTP %s' % code)
check('匿名没造成任何写入', 'zz_anon_probe' not in table_names(), '')

print('== ⑩ 缺口 1 回归：加表不会让引擎自检报失配 ==')
_, eng = call('GET', '/api/health/engine')
check('declarationMode = metadata-only（引擎不再写死表清单）',
      (eng or {}).get('declarationMode') == 'metadata-only', eng)
check('/api/health/engine consistent = true', (eng or {}).get('consistent') is True, eng)
check('metadataNotDeclared 为空（未声明不再被当成失配）',
      (eng or {}).get('metadataNotDeclared') == [], (eng or {}).get('metadataNotDeclared'))

print('== ⑪ 收尾：无残留 ==')
check('临时表已清理', TABLE not in table_names(), table_names())
check('临时字段已清理', FIELD not in field_names(TABLE), '')

print('\n结果：%d 通过 / %d 失败' % (pass_n, fail_n))
if failures:
    print('失败项：')
    for f in failures:
        print('  - %s' % f)
sys.exit(0 if fail_n == 0 else 1)

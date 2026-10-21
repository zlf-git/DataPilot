# -*- coding: utf-8 -*-
"""
Ticket 16 验收：文档与 seed 口径收口（纯 grep，不连服务、不跑 JVM）。

四个收口点各自有可机械校验的证据，写成脚本是为了「改文档时不会悄悄破坏」：
  D1 编号唯一      —— 闸门编号只有五道闸门.md 一处定义，别处不再写「闸门 3」这种半角数字
  C4/C5 口径       —— seed 与 Ontology定义.md 都写了「含未支付明细」「跨期不可加」
  G1 自检阈值      —— 结果自检的两个阈值是代码常量，gate_config 里**不该**有 max_abs_gmv/null_ratio
  E2 验收公式      —— 评测集.md 写死六个指标的分母与「延迟含 LLM」

用法：python check_docs_consistency.py        退出码 0 通过 / 1 有断言失败
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

fails = []
checks = []


def check(name, ok, detail=''):
    checks.append(name)
    print(('  [PASS] ' if ok else '  [FAIL] ') + name + (('  ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(*parts):
    return io.open(os.path.join(ROOT, *parts), encoding='utf-8').read()


def walk(sub, exts):
    for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, sub)):
        dirnames[:] = [d for d in dirnames if d not in ('node_modules', 'target', 'dist')]
        for f in filenames:
            if f.endswith(exts):
                yield os.path.join(dirpath, f)


def rel(p):
    return os.path.relpath(p, ROOT)


print('== D1 闸门编号唯一 ==')
gates = read('docs', '五道闸门.md')
for sym in '①②③④⑤':
    check('五道闸门.md 有「闸门%s」标题' % sym, ('## 闸门%s' % sym) in gates)
check('五道闸门.md 有编号约定段', '## 编号约定（唯一编号源' in gates)

# 半角数字编号是历史遗留：除五道闸门.md 的约定说明里举例，别处不该再出现。
# QA-*.md 是第三方评审/测试报告，属历史原件，**不改也不纳入本检查**——
# 改别人的报告来让 grep 变绿，等于伪造评审记录。
legacy = []
for p in list(walk('docs', '.md')):
    if p.endswith('五道闸门.md') or os.path.basename(p).startswith('QA-'):
        continue
    for i, line in enumerate(io.open(p, encoding='utf-8', errors='replace'), 1):
        if re.search(r'闸门\s*[0-9]', line):
            legacy.append('%s:%d' % (rel(p), i))
check('文档里没有「闸门 3」这类半角编号残留', not legacy, legacy[:5])

# 代码里的闸门名不带编号（Gate.name() 只有中文名）
names = []
for p in walk(os.path.join('src', 'main', 'java'), '.java'):
    if '/gate/' not in p.replace('\\', '/'):
        continue
    src = io.open(p, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'return "([^"]+)";', src):
        if m.group(1) in ('存在性', '权限', '代价', '语法', '结果自检'):
            names.append(m.group(1))
check('闸门实现返回的 name 是纯中文名（无编号）', len(names) >= 5, names)

print('== C4/C5 口径注释 ==')
seed = read('deploy', 'seed.sql')
check('seed 头部写明「含未支付明细」', '含未支付明细' in seed)
check('seed 头部写明「跨期不可加」', '跨期不可加' in seed)
check('seed 指标段有 C4④ 提示', 'C4④' in seed)
check('seed 指标段有 C5② 提示', 'C5②' in seed)
check('field_metadata 的 sale_amount 注释仍带口径', '成交金额（含未支付明细）' in seed)

onto = read('docs', 'Ontology定义.md')
check('Ontology定义.md 有 6.5 边界小节', '### 6.5 两条必须写在口径里的边界' in onto)
check('Ontology定义.md 写了商品销售额含未支付', '含未支付明细' in onto)
check('Ontology定义.md 写了支付用户数跨期不可加', '跨期不可加' in onto)

print('== G1 结果自检阈值写死常量 ==')
gate_src = read('src', 'main', 'java', 'com', 'zlf', 'datapilot', 'agent', 'gate', 'ResultCheckGate.java')
check('负值容差是常量（NEGATIVE_VALUE_TOLERANCE）', 'NEGATIVE_VALUE_TOLERANCE' in gate_src)
check('空值比例是常量（MAX_NULL_RATIO）', 'MAX_NULL_RATIO' in gate_src)
check('阈值不从 gate_config 读（只有 max_rows 读配置）',
      gate_src.count('cache.gateThreshold') == 1 and 'max_rows' in gate_src)
check('gate_config 里没有 max_abs_gmv / null_ratio',
      'max_abs_gmv' not in seed and 'null_ratio' not in seed)

print('== E2 验收公式 ==')
evals = read('docs', '评测集.md')
check('评测集.md 有 3.1 六层指标口径小节', '### 3.1 六层指标的分母与口径' in evals)
check('写明分母一律 15', '分母 = 15' in evals)
check('写明闸门拦截率分母是全集而非可执行题', '被任一闸门 `REJECT` 的题数 / 15' in evals)
check('写明平均延迟含 LLM 调用', '含** LLM 调用' in evals or '含 llm' in evals.lower())
check('写明 stub 与真模型的延迟分开报', 'provider' in evals)

print('')
if fails:
    print('FAIL：%d/%d 项未通过 → %s' % (len(fails), len(checks), '；'.join(fails)))
    sys.exit(1)
print('PASS：%d/%d 项全绿' % (len(checks), len(checks)))

import { Card, Tag, Tooltip, Typography, Space, Empty } from 'antd'

const { Text } = Typography

/**
 * 链路证据面板：五道闸门 → SQL → IR。
 *
 * 为什么闸门要逐条列出而不是给一个「通过」：
 * 用户真正想知道的是「这条 SQL 为什么可以跑」。五道闸门各自 PASS/REJECT 才是答案；
 * 一个总结性的绿灯什么都不说明，出了事也无从复盘。
 */
export default function ChainPanel({ result }) {
  const gates = result?.gates ?? []

  return (
    <Card size="small" title="链路证据" style={{ minHeight: 240 }}>
      {!result && <Empty description="还没有查询" image={Empty.PRESENTED_IMAGE_SIMPLE} />}

      {result && (
        <>
          <div className="section-title">五道闸门</div>
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            {gates.length === 0 && <Text type="secondary">这次没跑到闸门</Text>}
            {gates.map((g) => {
              // 后端格式是「闸门名=PASS / REJECT / PASS(WARN)」
              const [name, verdictRaw] = String(g).split('=')
              const verdict = (verdictRaw || '').trim()
              const color = verdict.startsWith('PASS')
                ? verdict.includes('WARN')
                  ? 'gold'
                  : 'green'
                : 'red'
              return (
                <div key={g} style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                  <Text style={{ fontSize: 13 }}>{name}</Text>
                  <Tag color={color} style={{ marginInlineEnd: 0 }}>
                    {verdict || '-'}
                  </Tag>
                </div>
              )
            })}
          </Space>

          <div className="section-title">SQL（参数化，值不在文本里）</div>
          <pre className="code-block">{result.sql || '（无）'}</pre>

          <div className="section-title">绑定参数</div>
          <pre className="code-block" style={{ background: '#1f2937' }}>
            {JSON.stringify(result.params ?? [], null, 2)}
          </pre>

          {result.comparisonSql && (
            <>
              <div className="section-title">
                环比 SQL
                <Tooltip title="环比那次的 SQL 文本与本期逐字相同（日期都是 ?），所以必须同时看下面的参数才能判断窗口是否前移">
                  <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>
                    （看参数才看得出前移）
                  </Text>
                </Tooltip>
              </div>
              <pre className="code-block" style={{ background: '#1f2937' }}>
                {result.comparisonSql}
              </pre>
              <pre className="code-block" style={{ background: '#111827' }}>
                {JSON.stringify(result.comparisonParams ?? [], null, 2)}
              </pre>
            </>
          )}

          <div className="section-title">IR（LLM 的产出，前端与后端之间只走它）</div>
          <pre className="code-block">{JSON.stringify(result.ir ?? null, null, 2)}</pre>
        </>
      )}
    </Card>
  )
}

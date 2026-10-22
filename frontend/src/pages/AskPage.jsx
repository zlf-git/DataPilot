import { useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Empty,
  Input,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import { api } from '../api.js'
import ChainPanel from '../components/ChainPanel.jsx'
import PreviewDrawer from '../components/PreviewDrawer.jsx'

const { TextArea } = Input
const { Text, Paragraph } = Typography

const EXAMPLES = [
  '10月19日小程序渠道的GMV是多少',
  '10月19日各渠道的支付转化率分别是多少？按从高到低排序',
  '近7天各省份的GMV排名',
  '最近一周支付订单数的环比怎么样',
]

const STATUS_META = {
  SUCCESS: { color: 'green', text: '成功' },
  PREVIEW: { color: 'blue', text: '已预览（未执行）' },
  REJECTED: { color: 'orange', text: '被拦下' },
  FAILED: { color: 'red', text: '失败' },
}

/**
 * 问数页（Ticket 13 核心页面）。
 *
 * 页面结构刻意分左右两栏：
 *  - 左：用户提问 + 结果（answer / 报表 / 明细表）
 *  - 右：链路证据（IR → SQL → 五道闸门）
 * 这不是「装饰性分区」，项目卖点就是「不搞黑盒」—— 同一个问题，用户看结论，
 * 面试官/审计看右边那条链路，谁都不用信一句「模型说对」。
 *
 * 两种入口都保留：
 *  - 「直接问」一步到底（/api/ask）；
 *  - 「先预览」只校验不执行，改完过滤条件再确认执行（/api/ask/preview + /execute）。
 *    强力场景才需要两段，所以不做成强制。
 */
export default function AskPage() {
  const { message } = AntApp.useApp()

  const [question, setQuestion] = useState(EXAMPLES[0])
  // sessionId 在前端生成并保持：多轮追问（「那环比呢」）靠它把上下文串起来
  const [sessionId, setSessionId] = useState(() => `web-${Date.now().toString(36)}`)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewResult, setPreviewResult] = useState(null)

  const runAsk = async () => {
    setLoading(true)
    setError(null)
    try {
      const r = await api.ask(question, sessionId)
      setResult(r)
      if (r.status !== 'SUCCESS') {
        message.warning(r.answer || '这次没有跑成功，右侧链路里有原因')
      }
    } catch (e) {
      setError(e.message)
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  const runPreview = async () => {
    setLoading(true)
    setError(null)
    try {
      const r = await api.preview(question, sessionId)
      if (r.status === 'PREVIEW') {
        setPreviewResult(r)
        setPreviewOpen(true)
      } else {
        setResult(r)
        message.warning(r.answer || '预览没通过校验，右侧链路里有原因')
      }
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  const onExecuted = (r) => {
    setPreviewOpen(false)
    setResult(r)
    // 执行完清掉预览态：这份快照已被消费，再点「执行」只会拿到「已过期」
    setPreviewResult(null)
    if (r.status === 'SUCCESS') {
      message.success('已执行')
    } else {
      message.warning(r.answer || '执行被拦下')
    }
  }

  const rows = result?.rows ?? []
  const columns = useMemo(() => {
    if (!rows.length) return []
    return Object.keys(rows[0]).map((k) => ({
      title: k,
      dataIndex: k,
      key: k,
      render: (v) => (v === null || v === undefined ? <Text type="secondary">NULL</Text> : String(v)),
    }))
  }, [rows])

  const statusMeta = STATUS_META[result?.status] || { color: 'default', text: result?.status || '-' }

  return (
    <Row gutter={16}>
      {/* ---------------- 左：提问 + 结果 ---------------- */}
      <Col span={14}>
        <Card size="small" title="提问">
          <TextArea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            autoSize={{ minRows: 2, maxRows: 4 }}
            placeholder="用中文问，如：10月19日各渠道的支付转化率分别是多少"
          />
          <Space wrap style={{ marginTop: 8 }}>
            {EXAMPLES.map((q) => (
              <Tag key={q} style={{ cursor: 'pointer' }} onClick={() => setQuestion(q)}>
                {q}
              </Tag>
            ))}
          </Space>
          <Space style={{ marginTop: 12 }} wrap>
            <Button type="primary" loading={loading} onClick={runAsk}>
              直接问
            </Button>
            <Button loading={loading} onClick={runPreview}>
              先预览（可改过滤条件）
            </Button>
            <Input
              addonBefore="会话"
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
              style={{ width: 260 }}
            />
            <Button
              onClick={() => {
                setSessionId(`web-${Date.now().toString(36)}`)
                setResult(null)
                message.info('已开新会话，多轮上下文清空')
              }}
            >
              新会话
            </Button>
          </Space>
        </Card>

        {error && (
          <Alert style={{ marginTop: 12 }} type="error" showIcon message="请求失败" description={error} />
        )}

        {result && (
          <Card
            size="small"
            style={{ marginTop: 12 }}
            title={
              <Space>
                <span>结果</span>
                <Tag color={statusMeta.color}>{statusMeta.text}</Tag>
                {result.rewrittenQuestion && result.rewrittenQuestion !== result.question && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    理解成：{result.rewrittenQuestion}
                  </Text>
                )}
              </Space>
            }
          >
            {result.answer && (
              <Paragraph style={{ fontSize: 14, marginBottom: 8 }} copyable={{ text: result.answer }}>
                {result.answer}
              </Paragraph>
            )}

            {/*
              Ticket 21：有图必须有结论。
              findings 是「这些数说明了什么」，gaps 是「问题要求了但这次取数覆盖不到的部分」。
              两者分开渲染：结论用普通列表，缺口用告警色 —— 缺口不代表失败（SQL 跑通了、数字是真的），
              但必须让人一眼看出这份答案是完整的还是残缺的，而不是默默给一张图让人以为答完了。
            */}
            {(result.analysis?.findings?.length > 0 || result.analysis?.gaps?.length > 0) && (
              <Card
                size="small"
                type="inner"
                title="分析"
                style={{ marginBottom: 8 }}
                styles={{ body: { paddingTop: 8, paddingBottom: 8 } }}
              >
                {result.analysis.findings?.length > 0 && (
                  <ol style={{ margin: 0, paddingLeft: 20, fontSize: 13, lineHeight: '22px' }}>
                    {result.analysis.findings.map((f) => (
                      <li key={f}>{f}</li>
                    ))}
                  </ol>
                )}
                {result.analysis.gaps?.length > 0 && (
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: result.analysis.findings?.length > 0 ? 10 : 0 }}
                    message="以下部分本次答不了"
                    description={
                      <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
                        {result.analysis.gaps.map((g) => (
                          <li key={g}>{g}</li>
                        ))}
                      </ul>
                    }
                  />
                )}
              </Card>
            )}

            {result.comparisonNote && (
              <Alert type="info" showIcon style={{ marginBottom: 8 }} message={result.comparisonNote} />
            )}

            {/* 结构性编辑被拒时，服务端逐条列出「你改的是问题不是参数」 */}
            {result.structuralChanges?.length > 0 && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 8 }}
                message="这次编辑越界了，未执行"
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {result.structuralChanges.map((s) => (
                      <li key={s}>{s}</li>
                    ))}
                  </ul>
                }
              />
            )}
            {result.edits?.length > 0 && (
              <Alert
                type="success"
                showIcon
                style={{ marginBottom: 8 }}
                message={`服务端确认生效的编辑：${result.edits.join('；')}`}
              />
            )}

            {rows.length > 0 ? (
              <Table
                size="small"
                dataSource={rows.map((r, i) => ({ ...r, __k: i }))}
                columns={columns}
                rowKey="__k"
                pagination={rows.length > 20 ? { pageSize: 20 } : false}
                scroll={{ x: 'max-content' }}
              />
            ) : (
              <Empty
                description={
                  result.status === 'SUCCESS' ? '查询成功但没有数据' : '没有结果（这次没执行到查询）'
                }
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            )}

            <Row gutter={16} style={{ marginTop: 12 }}>
              <Col span={6}>
                <Statistic title="耗时" value={result.costMs ?? 0} suffix="ms" valueStyle={{ fontSize: 16 }} />
              </Col>
              <Col span={6}>
                <Statistic title="token" value={result.totalTokens ?? 0} valueStyle={{ fontSize: 16 }} />
              </Col>
              <Col span={12}>
                <Descriptions size="small" column={1} style={{ marginTop: 4 }}>
                  <Descriptions.Item label="traceId">
                    <Text className="mono" style={{ fontSize: 12 }} copyable={{ text: result.traceId || '' }}>
                      {result.traceId || '-'}
                    </Text>
                  </Descriptions.Item>
                </Descriptions>
              </Col>
            </Row>
          </Card>
        )}

        {result?.reportHtml && (
          <Card size="small" title="分析报告（后端生成的独立 HTML）" style={{ marginTop: 12 }}>
            {/* reportHtml 是一整篇 HTML（含 ECharts 脚本），内联注入会污染本页样式与脚本作用域，
                所以用 iframe srcDoc + sandbox=allow-scripts 隔开：能画图，但碰不到父页面 */}
            <iframe className="report-frame" title="report" sandbox="allow-scripts" srcDoc={result.reportHtml} />
          </Card>
        )}
      </Col>

      {/* ---------------- 右：链路证据 ---------------- */}
      <Col span={10}>
        <ChainPanel result={result} />
        <Card
          size="small"
          title="这一步发生了什么"
          style={{ marginTop: 12 }}
          styles={{ body: { fontSize: 12.5, color: '#4e5969', lineHeight: 1.9 } }}
        >
          <div>① 改写：多轮追问补全（环比 / 指代），拼不出来就明确拒绝，不猜。</div>
          <div>② 召回：指标口径 + 字段元数据 + few-shot。</div>
          <div>③ 生成：LLM 只出 IR，不出 SQL。</div>
          <div>④ 闸门①②③：存在性 / 权限 / 代价，在 IR 层就拦。</div>
          <div>⑤ IR→SQL 参数化转换 + 闸门④语法（JSqlParser 二次提取比对）。</div>
          <div>⑥ 执行：只读账号，只跑我们拼出来的 SQL。</div>
          <div>⑦ 闸门⑤结果自检 + 环比 + 解释 + 报告 + 记会话。</div>
          <Collapse
            ghost
            size="small"
            items={[
              {
                key: 'why',
                label: '为什么前端不拼 SQL',
                children: (
                  <div>
                    前端只会调 /api/ask，拿回来的是结果与证据。执行入口只认 IR，裸 SQL 没有入口 ——
                    这正是「语义层约束生成空间」的落地方式。
                  </div>
                ),
              },
            ]}
          />
        </Card>
      </Col>

      <PreviewDrawer
        open={previewOpen}
        preview={previewResult}
        onClose={() => setPreviewOpen(false)}
        onExecuted={onExecuted}
      />
    </Row>
  )
}

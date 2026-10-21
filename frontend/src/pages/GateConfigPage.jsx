import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Space,
  Table,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import { api } from '../api.js'

const { Text } = Typography

/** 每个参数的语义说明（管理员调阈值时最需要的其实是「这条改了会怎样」） */
const PARAM_HINT = {
  max_join: 'IR 里允许的最大 JOIN 表数。调大 = 放开多表关联，代价闸门随之变松。',
  max_metric: '一次查询允许的指标个数上限。',
  max_complexity:
    '复杂度分值的拒绝点：指标数 × 维度数 × join 数（各因子下限 1，最简查询得 1），分值 ≥ 此值即拒绝。调到 1 连「当天总 GMV」都会被拦。',
  max_limit: '生成器允许写进 SQL 的 LIMIT 上限。',
  max_rows: '结果行数兜底：超过即由结果自检闸门告警。前端展示也受限。',
}

/**
 * 闸门配置页（管理员）：gate_config 阈值调整。
 *
 * 写入会被后端按 param_key 归一化 gate_name，并强制 param_value 为正整数 ——
 * 负数等于把闸门关掉，字符串会让代价闸门 Integer.parseInt 直接炸，这两类必须拦在写之前。
 */
export default function GateConfigPage() {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState([])
  const [drafts, setDrafts] = useState({})
  const [who, setWho] = useState('admin')
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const list = await api.gateConfigs()
      setRows(list)
      setDrafts(Object.fromEntries(list.map((g) => [g.paramKey, Number(g.paramValue)])))
    } catch (e) {
      message.error(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const save = async (record) => {
    const v = drafts[record.paramKey]
    try {
      await api.putGateConfig({ ...record, paramValue: String(v), updatedBy: who })
      message.success(`${record.paramKey} 已改为 ${v}，闸门立即生效`)
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const columns = [
    { title: '闸门', dataIndex: 'gateName', width: 110 },
    { title: '参数', dataIndex: 'paramKey', width: 140, render: (v) => <Text className="mono">{v}</Text> },
    {
      title: '当前值',
      dataIndex: 'paramValue',
      width: 110,
      render: (v) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: '改为',
      width: 160,
      render: (_, r) => (
        <InputNumber
          min={1}
          value={drafts[r.paramKey]}
          onChange={(v) => setDrafts({ ...drafts, [r.paramKey]: v })}
          style={{ width: 120 }}
        />
      ),
    },
    {
      title: '说明',
      dataIndex: 'paramKey',
      render: (v) => <Text type="secondary" style={{ fontSize: 12 }}>{PARAM_HINT[v] || '-'}</Text>,
    },
    {
      title: '更新人 / 时间',
      width: 160,
      render: (_, r) => (
        <Text type="secondary" style={{ fontSize: 12 }}>
          {r.updatedBy || '-'}
          {r.updateTime ? ` · ${r.updateTime}` : ''}
        </Text>
      ),
    },
    {
      title: '操作',
      width: 80,
      render: (_, r) => (
        <Button
          size="small"
          type="link"
          disabled={drafts[r.paramKey] === Number(r.paramValue) || !drafts[r.paramKey]}
          onClick={() => save(r)}
        >
          保存
        </Button>
      ),
    },
  ]

  return (
    <Card
      size="small"
      title="闸门阈值（管理员）"
      extra={
        <Space>
          <Input addonBefore="修改人" value={who} onChange={(e) => setWho(e.target.value)} style={{ width: 200 }} />
          <Button size="small" onClick={load} loading={loading}>
            刷新
          </Button>
        </Space>
      }
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message="这里调松一条，等于放开一道安全边界"
        description="阈值只允许正整数（负数等于关闸门）。改完立即生效，不需要重启，也不需要清缓存 —— 白名单缓存监听元数据变更事件。"
      />
      <Table size="small" rowKey="paramKey" dataSource={rows} columns={columns} pagination={false} loading={loading} />
    </Card>
  )
}

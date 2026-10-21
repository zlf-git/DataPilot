import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import { api } from '../api.js'

const { Text } = Typography

const EMPTY = {
  metricId: '',
  metricName: '',
  metricAlias: '',
  department: '',
  metricSql: '',
  metricType: 'simple',
  tableName: '',
  dimensions: '',
  owner: '',
  status: '启用',
  denominatorTable: '',
  denominatorSql: '',
}

/**
 * 指标管理页：业务自助维护 metric_def（口径只定义一次）。
 *
 * 前端只做「填表 + 提示」，真正的口径校验在后端：
 * metric_sql 只允许 SUM/COUNT/AVG/MAX/MIN(字段) 或「聚合/聚合」比值式 ——
 * 参数化只绑过滤值、不绑指标表达式，所以口径写什么就注入什么，这条线不能靠前端自觉。
 */
export default function MetricsPage() {
  const { message } = AntApp.useApp()
  const [rows, setRows] = useState([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const [editing, setEditing] = useState(null)

  const load = async () => {
    setLoading(true)
    try {
      setRows(await api.metrics())
    } catch (e) {
      message.error(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const startEdit = (record) => {
    setEditing(record?.metricId ?? null)
    form.setFieldsValue(record ? { ...EMPTY, ...record } : EMPTY)
    setOpen(true)
  }

  const submit = async () => {
    const v = await form.validateFields()
    try {
      await api.putMetric(v)
      message.success('已保存，白名单缓存会立即刷新（不用重启）')
      setOpen(false)
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const columns = [
    { title: 'metric_id', dataIndex: 'metricId', width: 170, render: (v) => <Text className="mono">{v}</Text> },
    { title: '中文名', dataIndex: 'metricName', width: 130 },
    { title: '口径 SQL', dataIndex: 'metricSql', render: (v) => <Text className="mono" style={{ fontSize: 12 }}>{v}</Text> },
    { title: '类型', dataIndex: 'metricType', width: 90, render: (v) => <Tag>{v}</Tag> },
    { title: '主表', dataIndex: 'tableName', width: 200, render: (v) => v || <Text type="secondary">-</Text> },
    {
      title: '跨表分母',
      dataIndex: 'denominatorSql',
      width: 150,
      render: (v, r) =>
        v ? (
          <Text className="mono" style={{ fontSize: 12 }}>
            {r.denominatorTable}: {v}
          </Text>
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    { title: '维度', dataIndex: 'dimensions', width: 160, render: (v) => v || <Text type="secondary">-</Text> },
    { title: '部门', dataIndex: 'department', width: 90 },
    { title: '负责人', dataIndex: 'owner', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (v) => <Tag color={v === '启用' ? 'green' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作',
      width: 80,
      render: (_, r) => (
        <Button size="small" type="link" onClick={() => startEdit(r)}>
          编辑
        </Button>
      ),
    },
  ]

  return (
    <Card
      size="small"
      title={`指标口径（${rows.length} 条）`}
      extra={
        <Space>
          <Button size="small" onClick={load} loading={loading}>
            刷新
          </Button>
          <Button size="small" type="primary" onClick={() => startEdit(null)}>
            新增指标
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="口径写什么就注入什么 —— 所以只接受聚合式或比值式"
        description="SUM/COUNT/AVG/MAX/MIN(字段)、COUNT(*)，或「聚合/聚合」比值式（metric_type 标 composite）。跨表占比填 denominator_table + denominator_sql，分母必须自带聚合。metric_name 唯一：同名词冲突会被 400 拒绝，不会静默改写别人的指标。"
      />

      <Table
        size="small"
        rowKey="metricId"
        loading={loading}
        dataSource={rows}
        columns={columns}
        scroll={{ x: 1500 }}
        pagination={false}
      />

      <Modal
        title={editing ? `编辑指标 ${editing}` : '新增指标'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        width={640}
        okText="保存"
      >
        <Form form={form} layout="vertical" initialValues={EMPTY}>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="metricId"
              label="metric_id"
              rules={[{ required: true, message: '必填' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="gmv_total" disabled={!!editing} />
            </Form.Item>
            <Form.Item name="metricName" label="中文名" rules={[{ required: true, message: '必填' }]} style={{ flex: 1 }}>
              <Input placeholder="总GMV" />
            </Form.Item>
          </Space>
          <Form.Item name="metricSql" label="口径 SQL" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="SUM(total_gmv) 或 SUM(paid_order_cnt)/SUM(total_order_cnt)" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="metricType" label="类型" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'simple', label: 'simple 简单' },
                  { value: 'composite', label: 'composite 复合' },
                  { value: 'uv', label: 'uv 去重' },
                ]}
              />
            </Form.Item>
            <Form.Item name="status" label="状态" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: '启用', label: '启用' },
                  { value: '停用', label: '停用' },
                ]}
              />
            </Form.Item>
          </Space>
          <Form.Item name="tableName" label="主关联表">
            <Input placeholder="ads_trade_overview_di" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="denominatorTable" label="跨表分母表（选填）" style={{ flex: 1 }}>
              <Input placeholder="留空 = 同表比值" />
            </Form.Item>
            <Form.Item name="denominatorSql" label="分母口径（选填）" style={{ flex: 1 }}>
              <Input placeholder="SUM(total_gmv)" />
            </Form.Item>
          </Space>
          <Form.Item name="dimensions" label="可用维度（逗号分隔）">
            <Input placeholder="dt,channel_name" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="metricAlias" label="同义词（逗号分隔）" style={{ flex: 1 }}>
              <Input placeholder="总成交额,总交易额" />
            </Form.Item>
            <Form.Item name="department" label="归属部门" style={{ flex: 1 }}>
              <Input placeholder="交易" />
            </Form.Item>
            <Form.Item name="owner" label="负责人" style={{ flex: 1 }}>
              <Input placeholder="zlf" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </Card>
  )
}

import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import { api } from '../api.js'

const { Text } = Typography

const EMPTY_FIELD = { fieldName: '', fieldType: '', fieldComment: '', fieldRole: undefined, enumValues: '' }

/**
 * 元数据页（Ticket 13 要求「必需」）：表清单只读 + 字段白名单可编辑。
 *
 * 这一页不是「元数据的展示面板」，它是白名单的维护入口 ——
 * field_metadata 就是存在性/语法闸门的字段白名单。以前改一个字段要连数据库或改 seed.sql，
 * 现在页面上改完即生效（后端发布变更事件刷新缓存，不用重启）。
 */
export default function MetadataPage() {
  const { message } = AntApp.useApp()
  const [tables, setTables] = useState([])
  const [fields, setFields] = useState([])
  const [activeTable, setActiveTable] = useState(null)
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  // Ticket 19：「新增表」的弹窗与表单（此前表清单只读，加表只能手工连库 INSERT）
  const [tableOpen, setTableOpen] = useState(false)
  const [tableForm] = Form.useForm()
  const [writeEnabled, setWriteEnabled] = useState(true)

  const load = async () => {
    setLoading(true)
    try {
      const [t, f, health] = await Promise.all([api.tables(), api.fields(), api.healthMetadata()])
      setTables(t)
      setFields(f)
      setWriteEnabled(health.writeEnabled !== false)
      setActiveTable((prev) => prev ?? t[0]?.tableName ?? null)
    } catch (e) {
      message.error(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const tableRows = useMemo(
    () =>
      tables.map((t) => ({
        ...t,
        fieldCount: fields.filter((f) => f.tableName === t.tableName).length,
      })),
    [tables, fields],
  )

  const tableFields = useMemo(
    () => fields.filter((f) => f.tableName === activeTable),
    [fields, activeTable],
  )

  const startEdit = (record) => {
    setEditing(record ?? null)
    form.setFieldsValue(
      record
        ? { ...EMPTY_FIELD, ...record }
        : { ...EMPTY_FIELD, fieldName: '', fieldType: 'varchar' },
    )
    setOpen(true)
  }

  const submit = async () => {
    const v = await form.validateFields()
    try {
      // 「新增」与「编辑」必须走两种语义（QA-13 P2-2）：
      // 都用 upsert 时，新增一个已存在的字段名不会报重复，而是把原来的
      // field_role / field_comment / enum_values 静默盖掉 —— 后者一改，
      // 闸门对这条字段的校验口径就跟着变了，而操作者以为是「新建了一条」。
      await api.putField({ ...v, tableName: activeTable }, editing ? 'upsert' : 'create')
      message.success('已写入白名单，闸门立即可见（缓存已刷新）')
      setOpen(false)
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const remove = async (record) => {
    try {
      await api.deleteField(record.tableName, record.fieldName)
      message.success(`已摘掉 ${record.tableName}.${record.fieldName}`)
      load()
    } catch (e) {
      // 被维度字典/指标维度引用时后端会说清是谁在用，这里原样展示
      message.error(e.message)
    }
  }

  // Ticket 19：注册新表。此前表清单是只读的，加表只能手工 INSERT INTO semantic_model，
  // 绕过 write-enabled 急停与鉴权 —— 那不算产品能力。
  const submitTable = async () => {
    const v = await tableForm.validateFields()
    try {
      await api.putTable(v, 'create')
      message.success(`已注册 ${v.tableName}，接着给它加字段白名单`)
      setTableOpen(false)
      setActiveTable(v.tableName)
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const removeTable = async (record) => {
    try {
      await api.deleteTable(record.tableName)
      message.success(`已注销 ${record.tableName}`)
      load()
    } catch (e) {
      // 后端会列出是谁在引用（指标主表/分母表/维度字典/metric_table/字段白名单），原样展示
      message.error(e.message)
    }
  }

  const fieldColumns = [
    { title: '字段名', dataIndex: 'fieldName', width: 200, render: (v) => <Text className="mono">{v}</Text> },
    { title: '类型', dataIndex: 'fieldType', width: 130 },
    { title: '注释', dataIndex: 'fieldComment', render: (v) => v || <Text type="secondary">-</Text> },
    {
      title: '角色',
      dataIndex: 'fieldRole',
      width: 110,
      render: (v) =>
        v ? <Tag color={v === 'metric' ? 'blue' : 'cyan'}>{v}</Tag> : <Text type="secondary">未标</Text>,
    },
    {
      title: '枚举值',
      dataIndex: 'enumValues',
      width: 220,
      render: (v) =>
        v ? (
          <Text className="mono" style={{ fontSize: 12 }}>
            {v}
          </Text>
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    {
      title: '操作',
      width: 150,
      render: (_, r) => (
        <Space size={0}>
          <Button size="small" type="link" disabled={!writeEnabled} onClick={() => startEdit(r)}>
            编辑
          </Button>
          <Popconfirm
            title="从白名单摘掉这个字段？"
            description="被维度字典或指标维度引用时会被拒绝"
            onConfirm={() => remove(r)}
            okText="摘掉"
            cancelText="取消"
            disabled={!writeEnabled}
          >
            <Button size="small" type="link" danger disabled={!writeEnabled}>
              移出
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <>
      {!writeEnabled && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="写入已急停（datapilot.metadata.write-enabled=false）"
          description="页面仍可浏览，但新增/编辑/移出白名单会被后端 403 拒绝。这是运维开关，不是权限：即使有 admin 凭证也写不进去，需要重启并置 true。"
        />
      )}
      <Row gutter={12}>
        <Col span={8}>
          <Card
            size="small"
            title={`表（${tableRows.length}）`}
            extra={
              <Space size={8}>
                <Button size="small" onClick={load} loading={loading}>
                  刷新
                </Button>
                <Button
                  size="small"
                  type="primary"
                  disabled={!writeEnabled}
                  onClick={() => {
                    tableForm.setFieldsValue({ tableName: '', tableComment: '', bizDomain: '' })
                    setTableOpen(true)
                  }}
                >
                  新增表
                </Button>
              </Space>
            }
          >
            <Table
              size="small"
              rowKey="tableName"
              dataSource={tableRows}
              pagination={false}
              loading={loading}
              rowClassName={(r) => (r.tableName === activeTable ? 'ant-table-row-selected' : '')}
              onRow={(r) => ({ onClick: () => setActiveTable(r.tableName), style: { cursor: 'pointer' } })}
              columns={[
                { title: '表名', dataIndex: 'tableName', render: (v) => <Text className="mono" style={{ fontSize: 12 }}>{v}</Text> },
                { title: '业务域', dataIndex: 'bizDomain', width: 80 },
                { title: '字段数', dataIndex: 'fieldCount', width: 70 },
                {
                  title: '操作',
                  width: 80,
                  render: (_, r) => (
                    <Popconfirm
                      title="注销这张表？"
                      description="还有字段/指标/维度引用时会被拒绝，不会级联删除"
                      onConfirm={() => removeTable(r)}
                      okText="注销"
                      cancelText="取消"
                      disabled={!writeEnabled}
                    >
                      {/* 整行有 onClick 切表，按钮必须阻止冒泡，否则点删除会把左表切过去 */}
                      <Button
                        size="small"
                        type="link"
                        danger
                        disabled={!writeEnabled}
                        onClick={(e) => e.stopPropagation()}
                      >
                        注销
                      </Button>
                    </Popconfirm>
                  ),
                },
              ]}
            />
          </Card>
        </Col>
        <Col span={16}>
          <Card
            size="small"
            title={`字段白名单 · ${activeTable ?? '-'}（${tableFields.length}）`}
            extra={
              <Button size="small" type="primary" disabled={!writeEnabled || !activeTable} onClick={() => startEdit(null)}>
                新增字段
              </Button>
            }
          >
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="这张表就是闸门的字段白名单"
              description="字段不在白名单里 → 存在性/语法闸门直接拒绝。加了字段立刻生效，不用重启；removed 之前请先清掉维度字典与指标可用维度里的引用。"
            />
            <Table
              size="small"
              rowKey={(r) => `${r.tableName}.${r.fieldName}`}
              dataSource={tableFields}
              columns={fieldColumns}
              pagination={false}
              scroll={{ x: 900 }}
            />
          </Card>
        </Col>
      </Row>

      <Modal
        title={editing ? `编辑字段 ${editing.fieldName}` : `新增字段 · ${activeTable}`}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        okText="保存"
        width={560}
      >
        <Form form={form} layout="vertical" initialValues={EMPTY_FIELD}>
          <Form.Item
            name="fieldName"
            label="字段名"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^[a-z_][a-z0-9_]*$/, message: '只允许小写字母/数字/下划线，字母或下划线开头' },
            ]}
          >
            <Input placeholder="channel_name" disabled={!!editing} />
          </Form.Item>
          <Form.Item name="fieldType" label="类型">
            <Input placeholder="varchar(32)" />
          </Form.Item>
          <Form.Item name="fieldComment" label="业务注释（NL2SQL 准确率的关键）">
            <Input placeholder="渠道名称" />
          </Form.Item>
          <Form.Item name="fieldRole" label="角色">
            <Select
              allowClear
              placeholder="dimension / metric / 不标"
              options={[
                { value: 'dimension', label: 'dimension 维度' },
                { value: 'metric', label: 'metric 指标' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="enumValues"
            label="枚举值（JSON 数组）"
            rules={[
              {
                validator: (_, v) => {
                  if (!v) return Promise.resolve()
                  try {
                    const parsed = JSON.parse(v)
                    return Array.isArray(parsed)
                      ? Promise.resolve()
                      : Promise.reject(new Error('必须是 JSON 数组，如 ["APP","小程序"]'))
                  } catch {
                    return Promise.reject(new Error('不是合法 JSON'))
                  }
                },
              },
            ]}
          >
            <Input.TextArea autoSize={{ minRows: 2 }} placeholder='["APP","小程序","网页"]' />
          </Form.Item>
        </Form>
      </Modal>

      {/* Ticket 19：新增表。注册完自动选中它，接着就能加字段 —— 这就是「加表零改动」的完整操作路径 */}
      <Modal
        title="新增表"
        open={tableOpen}
        onCancel={() => setTableOpen(false)}
        onOk={submitTable}
        okText="注册"
        width={520}
      >
        <Form form={tableForm} layout="vertical">
          <Form.Item
            name="tableName"
            label="表名（必须与引擎里的物理表同名）"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^[a-z_][a-z0-9_]*$/, message: '只允许小写字母/数字/下划线，字母或下划线开头' },
            ]}
            extra="写错名字的后果不是页面难看，而是白名单里挂着一张永远匹配不上的表（闸门①会报「指标关联的表不在白名单」）"
          >
            <Input placeholder="ads_new_overview_di" />
          </Form.Item>
          <Form.Item name="tableComment" label="表说明">
            <Input placeholder="新渠道总览（渠道日汇总）" />
          </Form.Item>
          <Form.Item name="bizDomain" label="业务域">
            <Input placeholder="交易 / 渠道 / 地域 / 商品" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

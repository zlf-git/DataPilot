import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Drawer,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Typography,
  App as AntApp,
} from 'antd'
import { api } from '../api.js'

const { Text } = Typography

/**
 * 预览确认抽屉（Ticket 12 两段的前端一半）。
 *
 * 只暴露服务端允许的四类编辑：过滤值 / limit / 排序方向 / 维度。
 * 指标、查询模式、时间窗口在这里是只读的 —— 它们改变的是「问的是哪个问题」，
 * 硬改会被服务端判定为结构变更并拒掉。与其让用户改完被拒，不如一开始就不给改。
 */
export default function PreviewDrawer({ open, preview, onClose, onExecuted }) {
  const { message } = AntApp.useApp()
  const [values, setValues] = useState([])
  const [limit, setLimit] = useState(null)
  // 代价闸门的 max_limit（默认 1000，进抽屉时向 /api/gate-config 取真值）
  const [maxLimit, setMaxLimit] = useState(1000)
  const [dirs, setDirs] = useState([])
  const [dims, setDims] = useState([])
  const [fieldOptions, setFieldOptions] = useState([])
  const [executing, setExecuting] = useState(false)

  const ir = preview?.ir

  // 每次打开都用快照重置草稿：上一份预览的编辑不能带到下一份里去
  useEffect(() => {
    if (!open || !ir) return
    setValues(
      (ir.filters ?? []).map((f) =>
        typeof f.value === 'string'
          ? { raw: f.value, wasString: true }
          : { raw: JSON.stringify(f.value ?? null), wasString: false },
      ),
    )
    setLimit(ir.limit ?? null)
    setDirs((ir.orderBy ?? []).map((o) => o.direction ?? 'asc'))
    setDims((ir.dimensions ?? []).map((d) => d.field))
  }, [open, ir])

  useEffect(() => {
    if (!open) return
    api
      .fields()
      .then((list) => setFieldOptions([...new Set(list.map((f) => f.fieldName))]))
      .catch(() => setFieldOptions([]))
  }, [open])

  // limit 的输入上限取代价闸门的真值（QA-13 P3-6）：写死 5000 时，
  // 1001~5000 是「前端允许填、代价闸门必拒」，用户会以为是自己填错了。
  useEffect(() => {
    if (!open) return
    api
      .gateConfigs()
      .then((list) => {
        const row = (list || []).find((g) => g && g.paramKey === 'max_limit')
        const v = row && Number(row.paramValue)
        if (v && v > 0) setMaxLimit(v)
      })
      .catch(() => {
        // 取不到就保留默认 1000：这只是个输入框上限，不该因为它让整个抽屉打不开
      })
  }, [open])

  const buildIr = () => {
    const next = JSON.parse(JSON.stringify(ir))
    next.filters = (next.filters ?? []).map((f, i) => {
      const v = values[i]
      if (!v) return f
      let parsed = v.raw
      if (!v.wasString) {
        try {
          parsed = JSON.parse(v.raw)
        } catch {
          // 写了一半的 JSON 就按字面量提交：让服务端按参数绑定的语义去处理，
          // 前端不做「猜用户想写什么数组」这种判断
          parsed = v.raw
        }
      }
      return { ...f, value: parsed }
    })
    next.limit = limit
    if (next.orderBy) {
      next.orderBy = next.orderBy.map((o, i) => ({ ...o, direction: dirs[i] ?? o.direction }))
    }
    // 保留 alias（QA-13 P3-5）：这里只该改「有哪些维度」，不该顺手抹掉维度别名。
    // 当前桩 IR 没有 alias 所以看不出来，但 LLM 真链路会产出 alias ——
    // 抹掉它等于改了展示列名，而且只在「动过抽屉任何一项」之后才发生，极难复盘。
    const aliasOf = new Map(
      (ir.dimensions ?? []).filter((d) => d && d.field && d.alias).map((d) => [d.field, d.alias]),
    )
    next.dimensions = dims.map((d) => (aliasOf.has(d) ? { field: d, alias: aliasOf.get(d) } : { field: d }))
    return next
  }

  const execute = async (withEdits) => {
    if (!preview?.previewId) return
    setExecuting(true)
    try {
      const payload = withEdits ? buildIr() : null
      const r = await api.execute(preview.previewId, payload)
      onExecuted(r)
    } catch (e) {
      message.error(e.message)
    } finally {
      setExecuting(false)
    }
  }

  const gateTags = useMemo(() => {
    return (preview?.gates ?? []).map((g) => {
      const [name, verdict = ''] = String(g).split('=')
      return (
        <Tag key={g} color={verdict.startsWith('PASS') ? 'green' : 'red'}>
          {name} {verdict}
        </Tag>
      )
    })
  }, [preview])

  return (
    <Drawer
      title="预览确认（这一步还没有执行）"
      width={560}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button loading={executing} onClick={() => execute(false)}>
            按原样执行
          </Button>
          <Button type="primary" loading={executing} onClick={() => execute(true)}>
            执行改动
          </Button>
        </Space>
      }
    >
      {!preview && <Text type="secondary">没有预览内容</Text>}

      {preview && (
        <>
          <Alert
            type="info"
            showIcon
            message="预览只做校验，不碰数据库"
            description={
              <div>
                <div style={{ marginBottom: 6 }}>
                  下面这些闸门是在<b>IR 层与 SQL 层</b>跑的，执行时会全部重跑一遍（不是信任这份结论）。
                </div>
                <Space wrap size={4}>
                  {gateTags}
                </Space>
              </div>
            }
          />

          <div className="section-title">目标 SQL</div>
          <pre className="code-block">{preview.sql}</pre>

          <div className="section-title">可改：过滤条件的值（改值不产生注入，走参数绑定）</div>
          {(ir?.filters ?? []).length === 0 && <Text type="secondary">这个查询没有过滤条件</Text>}
          {(ir?.filters ?? []).map((f, i) => (
            <div key={`${f.field}-${i}`} style={{ marginBottom: 8 }}>
              <Text className="mono" style={{ fontSize: 12 }}>
                {f.field} {f.op}
              </Text>
              <Input
                value={values[i]?.raw ?? ''}
                onChange={(e) => {
                  const next = [...values]
                  next[i] = { ...(next[i] || { wasString: true }), raw: e.target.value }
                  setValues(next)
                }}
                style={{ marginTop: 2 }}
              />
            </div>
          ))}

          <div className="section-title">可改：条数上限 / 排序方向 / 维度</div>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <div>
              <Text type="secondary" style={{ fontSize: 12, marginRight: 8 }}>
                limit
              </Text>
              {/* 上限跟 /api/gate-config 的 max_limit 对齐（QA-13 P3-6）：
                  写死 5000 会让人填出 1001~5000 这种「前端允许、代价闸门必拒」的值，
                  用户以为是自己填错了，其实两边上限从来没对齐过。 */}
              <InputNumber min={1} max={maxLimit} value={limit} onChange={setLimit} style={{ width: 160 }} />
            </div>
            {(ir?.orderBy ?? []).map((o, i) => (
              <div key={`${o.field}-${i}`}>
                <Text className="mono" style={{ fontSize: 12, marginRight: 8 }}>
                  order by {o.field}
                </Text>
                <Select
                  value={dirs[i]}
                  onChange={(v) => {
                    const next = [...dirs]
                    next[i] = v
                    setDirs(next)
                  }}
                  style={{ width: 120 }}
                  options={[
                    { value: 'asc', label: '升序 asc' },
                    { value: 'desc', label: '降序 desc' },
                  ]}
                />
              </div>
            ))}
            <div>
              <Text type="secondary" style={{ fontSize: 12, marginRight: 8 }}>
                维度
              </Text>
              <Select
                mode="multiple"
                value={dims}
                onChange={setDims}
                style={{ width: '100%' }}
                placeholder="从字段白名单里选"
                options={fieldOptions.map((f) => ({ value: f, label: f }))}
              />
            </div>
          </Space>

          <Alert
            style={{ marginTop: 16 }}
            type="warning"
            showIcon
            message="改指标 / 时间窗口 / 过滤字段不算「调参」"
            description="那属于换一个问题的范畴，服务端会判定为结构变更并拒绝执行，请回去重新提问，让 LLM 重新生成一份完整走闸门的 IR。"
          />
        </>
      )}
    </Drawer>
  )
}

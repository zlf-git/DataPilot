import { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'

/**
 * 经营大屏（P1-A）：全屏只读看板，数据全部来自 `/api/dashboard/*`（走 StarRocks ADS 层）。
 *
 * <p>为什么不用 go-view：本机这套 go-view 只有 nginx 托管的静态壳，没有 go-view-server，
 * 打开就是登录页、进不去编辑器（详见 docs/platform/数据平台开发计划.md 的落地记录）。
 * 大屏本身是「4 个固定图 + 4 个固定卡片」，手写 SVG 零依赖、可测、可截图，反而更省事。
 *
 * <p>几条刻意的取舍：
 * <ul>
 *   <li>不用 antd：大屏是深色底，antd 的浅色组件要覆盖一堆 token，不如直接写 CSS。</li>
 *   <li>涨红跌绿：国内习惯，与欧美相反。</li>
 *   <li>30 秒轮询：ADS 是流式写入的，页面不刷新也能看到最新；离开页面即停。</li>
 * </ul>
 */

const C = {
  bg: '#08111f',
  bg2: '#0b1a2e',
  panel: 'rgba(15,35,60,0.72)',
  border: 'rgba(56,189,248,0.22)',
  text: '#e8f1ff',
  sub: '#7f9bc0',
  cyan: '#22d3ee',
  blue: '#4f8cff',
  amber: '#fbbf24',
  up: '#ef4444',
  down: '#22c55e',
}

const money = (v) => `¥${Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const num = (v) => Number(v || 0).toLocaleString('zh-CN')
const pct = (v, digits = 1) => (v === null || v === undefined ? '—' : `${(Number(v) * 100).toFixed(digits)}%`)

function Dod({ value }) {
  if (value === null || value === undefined) {
    return <span style={{ color: C.sub, fontSize: 12 }}>环比 —</span>
  }
  const up = Number(value) >= 0
  return (
    <span style={{ color: up ? C.up : C.down, fontSize: 12 }}>
      {up ? '▲' : '▼'} {Math.abs(Number(value) * 100).toFixed(1)}% vs 前一日
    </span>
  )
}

function KpiCard({ label, value, foot }) {
  return (
    <div data-kpi={label} style={{ background: C.panel, border: `1px solid ${C.border}`, borderRadius: 10, padding: '14px 18px' }}>
      <div style={{ color: C.sub, fontSize: 13, letterSpacing: 1 }}>{label}</div>
      <div style={{ color: C.text, fontSize: 28, fontWeight: 700, margin: '6px 0 4px', fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </div>
      {foot}
    </div>
  )
}

function Panel({ title, hint, children, style }) {
  return (
    <div data-panel={title} style={{ background: C.panel, border: `1px solid ${C.border}`, borderRadius: 10, padding: '12px 16px 8px', display: 'flex', flexDirection: 'column', minHeight: 0, ...style }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 6 }}>
        <span style={{ color: C.text, fontSize: 15, fontWeight: 600 }}>
          <span style={{ color: C.cyan }}>▍</span>
          {title}
        </span>
        {hint && <span style={{ color: C.sub, fontSize: 12 }}>{hint}</span>}
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>{children}</div>
    </div>
  )
}

/** 近 N 日 GMV 折线（手写 SVG，无图表库） */
function TrendChart({ rows }) {
  const W = 640
  const H = 230
  const pad = { l: 64, r: 16, t: 14, b: 26 }
  const iw = W - pad.l - pad.r
  const ih = H - pad.t - pad.b

  if (!rows.length) {
    return <div style={{ color: C.sub, fontSize: 13, padding: 20 }}>暂无数据</div>
  }

  const values = rows.map((r) => Number(r.totalGmv))
  const max = Math.max(...values)
  const min = Math.min(...values)
  // 上下各留 8% 余量，避免折线贴边；全等时人为撑开 10%
  const span = max - min || max * 0.1 || 1
  const top = max + span * 0.08
  const bottom = Math.max(0, min - span * 0.08)
  const x = (i) => pad.l + (rows.length === 1 ? iw / 2 : (iw * i) / (rows.length - 1))
  const y = (v) => pad.t + ih - ((v - bottom) / (top - bottom)) * ih

  const line = rows.map((r, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(values[i]).toFixed(1)}`).join(' ')
  const area = `${line} L${x(rows.length - 1).toFixed(1)},${pad.t + ih} L${x(0).toFixed(1)},${pad.t + ih} Z`
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => bottom + (top - bottom) * t)

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: '100%' }} preserveAspectRatio="none">
      <defs>
        <linearGradient id="gmvFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={C.cyan} stopOpacity="0.45" />
          <stop offset="100%" stopColor={C.cyan} stopOpacity="0.02" />
        </linearGradient>
      </defs>
      {ticks.map((v, i) => (
        <g key={i}>
          <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} stroke="rgba(127,155,192,0.16)" strokeDasharray="3 4" />
          <text x={pad.l - 8} y={y(v) + 4} fill={C.sub} fontSize="11" textAnchor="end">
            {(v / 10000).toFixed(1)}万
          </text>
        </g>
      ))}
      <path d={area} fill="url(#gmvFill)" />
      <path d={line} fill="none" stroke={C.cyan} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
      {rows.map((r, i) => (
        <g key={r.dt}>
          <circle data-point={r.dt} cx={x(i)} cy={y(values[i])} r="3.5" fill={C.bg} stroke={C.cyan} strokeWidth="2" />
          <text x={x(i)} y={H - 8} fill={C.sub} fontSize="11" textAnchor="middle">
            {String(r.dt).slice(5)}
          </text>
        </g>
      ))}
    </svg>
  )
}

/** 横向条形排行（渠道 / 地区 / 商品共用） */
function BarList({ items, valueOf, labelOf, extraOf, color = C.blue, formatter = money }) {
  if (!items.length) {
    return <div style={{ color: C.sub, fontSize: 13, padding: 16 }}>暂无数据</div>
  }
  const max = Math.max(...items.map((it) => Number(valueOf(it) || 0))) || 1
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '6px 0' }}>
      {items.map((it, i) => (
        <div key={i} data-bar={labelOf(it)}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 3 }}>
            <span style={{ color: C.text }}>
              <span style={{ color: C.sub, marginRight: 6 }}>{String(i + 1).padStart(2, '0')}</span>
              {labelOf(it)}
            </span>
            <span style={{ color: C.text, fontVariantNumeric: 'tabular-nums' }}>
              {formatter(valueOf(it))}
              {extraOf && <span style={{ color: C.sub, marginLeft: 8 }}>{extraOf(it)}</span>}
            </span>
          </div>
          <div style={{ height: 6, background: 'rgba(127,155,192,0.16)', borderRadius: 3, overflow: 'hidden' }}>
            <div
              style={{
                width: `${Math.max(2, (Number(valueOf(it) || 0) / max) * 100)}%`,
                height: '100%',
                borderRadius: 3,
                background: `linear-gradient(90deg, ${color}55, ${color})`,
              }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

export default function DashboardPage() {
  const [summary, setSummary] = useState(null)
  const [trend, setTrend] = useState([])
  const [channel, setChannel] = useState([])
  const [region, setRegion] = useState([])
  const [product, setProduct] = useState([])
  const [error, setError] = useState('')
  const [updatedAt, setUpdatedAt] = useState('')

  useEffect(() => {
    let alive = true

    const load = async () => {
      try {
        // 先取汇总拿到「最新业务日期」，其余四块都用同一天，避免跨天时前后不一致
        const s = await api.dashboardSummary()
        if (!alive) return
        setSummary(s)
        const dt = s && !s.empty ? s.dt : undefined
        const [t, c, r, p] = await Promise.all([
          api.dashboardTrend(7),
          api.dashboardChannel(dt),
          api.dashboardRegion(dt),
          api.dashboardProduct(dt, 8),
        ])
        if (!alive) return
        setTrend(t || [])
        setChannel(c || [])
        setRegion(r || [])
        setProduct(p || [])
        setError('')
        setUpdatedAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
      } catch (e) {
        if (alive) setError(e.message || '数据加载失败')
      }
    }

    load()
    const timer = setInterval(load, 30000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [])

  const channelTotal = useMemo(
    () => channel.reduce((acc, it) => acc + Number(it.gmv || 0), 0),
    [channel],
  )

  return (
    <div
      style={{
        minHeight: '100vh',
        background: `radial-gradient(1200px 600px at 20% -10%, #12304f 0%, ${C.bg} 55%), ${C.bg2}`,
        color: C.text,
        padding: '18px 22px 22px',
        display: 'flex',
        flexDirection: 'column',
        gap: 14,
        fontFamily: 'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 16 }}>
        <div style={{ fontSize: 26, fontWeight: 700, letterSpacing: 2 }}>
          <span style={{ color: C.cyan }}>DataPilot</span> · 抖音订单经营大屏
        </div>
        <div style={{ color: C.sub, fontSize: 13 }}>
          数据源 StarRocks ADS 层 · 业务日期 <span style={{ color: C.cyan }}>{summary?.dt || '—'}</span>
        </div>
        <div style={{ flex: 1 }} />
        {error ? (
          <div style={{ color: C.up, fontSize: 13 }}>● {error}</div>
        ) : (
          <div style={{ color: C.sub, fontSize: 13 }}>
            <span style={{ color: C.down }}>●</span> 30 秒自动刷新{updatedAt ? ` · 最近 ${updatedAt}` : ''}
          </div>
        )}
        <a href="/ask" style={{ color: C.cyan, fontSize: 13, textDecoration: 'none' }}>
          返回问数 →
        </a>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
        <KpiCard label="成交总额 GMV" value={money(summary?.totalGmv)} foot={<Dod value={summary?.gmvDod} />} />
        <KpiCard
          label="订单量 / 支付订单"
          value={`${num(summary?.totalOrderCnt)} / ${num(summary?.paidOrderCnt)}`}
          foot={<Dod value={summary?.paidOrderDod} />}
        />
        <KpiCard
          label="支付转化率"
          value={pct(summary?.payRate)}
          foot={<span style={{ color: C.sub, fontSize: 12 }}>支付订单 ÷ 订单量</span>}
        />
        <KpiCard
          label="客单价 / 支付用户"
          value={`${money(summary?.avgOrderValue)}`}
          foot={<span style={{ color: C.sub, fontSize: 12 }}>支付用户 {num(summary?.paidUserCnt)} 人</span>}
        />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1.35fr 1fr', gap: 14, flex: 1, minHeight: 300 }}>
        <Panel title="近 7 日 GMV 趋势" hint="单位：万元">
          <div style={{ height: '100%', minHeight: 200 }}>
            <TrendChart rows={trend} />
          </div>
        </Panel>
        <Panel title="渠道转化" hint={`当日 GMV 合计 ${money(channelTotal)}`}>
          <BarList
            items={channel}
            labelOf={(it) => it.channelName}
            valueOf={(it) => it.gmv}
            extraOf={(it) => `支付率 ${pct(it.payConversionRate)}`}
            color={C.cyan}
          />
        </Panel>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, flex: 1, minHeight: 260 }}>
        <Panel title="地区 GMV 排行" hint="按省份">
          <BarList
            items={region}
            labelOf={(it) => `${it.provinceName}（${it.region}）`}
            valueOf={(it) => it.gmv}
            extraOf={(it) => `${num(it.paidOrderCnt)} 单`}
            color={C.blue}
          />
        </Panel>
        <Panel title="商品销售 TOP 8" hint="按销售额">
          <BarList
            items={product}
            labelOf={(it) => `${it.productName} · ${it.categoryName}`}
            valueOf={(it) => it.saleAmount}
            extraOf={(it) => `${num(it.saleCnt)} 件`}
            color={C.amber}
          />
        </Panel>
      </div>
    </div>
  )
}

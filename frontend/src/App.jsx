import { useEffect, useState } from 'react'
import { Layout, Menu, Tag, Space, Typography, Button, Modal, Input, Alert, message } from 'antd'
import {
  MessageOutlined,
  ExperimentOutlined,
  DatabaseOutlined,
  SlidersOutlined,
  KeyOutlined,
  FundOutlined,
} from '@ant-design/icons'
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import AskPage from './pages/AskPage.jsx'
import MetricsPage from './pages/MetricsPage.jsx'
import MetadataPage from './pages/MetadataPage.jsx'
import GateConfigPage from './pages/GateConfigPage.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import { api, auth, UNAUTHORIZED_EVENT } from './api.js'

const { Header, Sider, Content } = Layout

const MENU = [
  { key: '/ask', icon: <MessageOutlined />, label: <Link to="/ask">问数</Link> },
  { key: '/metrics', icon: <ExperimentOutlined />, label: <Link to="/metrics">指标管理</Link> },
  { key: '/metadata', icon: <DatabaseOutlined />, label: <Link to="/metadata">元数据 / 白名单</Link> },
  { key: '/gate-config', icon: <SlidersOutlined />, label: <Link to="/gate-config">闸门配置</Link> },
  { key: '/dashboard', icon: <FundOutlined />, label: <Link to="/dashboard">经营大屏</Link> },
]

/**
 * 写入凭证弹窗（Ticket 14）。
 *
 * <p>Ticket 13 的页面靠 `write-enabled` 开关决定「能不能写」；Ticket 14 之后这个开关
 * 变成「运维急停」，能不能写由<b>身份</b>决定。页面没有登录页（票面明确不做），
 * 所以只在这里放一个「有需要时填一次」的凭证框，存 sessionStorage。
 *
 * <p>三种状态要能一眼看出来，否则用户点了保存只看到一片红：
 * 未设凭证（灰）/ 已设凭证（绿，显示用户名）/ 后端说凭证错（弹窗自动打开）。
 */
function CredentialModal({ open, onClose, onSaved }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  useEffect(() => {
    if (open) {
      setUsername(auth.user() || '')
      setPassword('')
    }
  }, [open])

  const save = () => {
    if (!username.trim() || !password) {
      message.warning('用户名和密码都要填')
      return
    }
    auth.set(username.trim(), password)
    message.success(`写入凭证已设置：${username.trim()}（只在本标签页有效）`)
    onSaved()
    onClose()
  }

  return (
    <Modal
      title="写入凭证"
      open={open}
      onCancel={onClose}
      onOk={save}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="读和问数不需要凭证；指标口径、闸门阈值、字段白名单的写入需要。"
        description="默认账号：admin / Admin@2026（全部写权限）、dba / Dba@2026（仅白名单）。凭证只存在本标签页的 sessionStorage。"
      />
      <Space direction="vertical" style={{ width: '100%' }}>
        <Input
          placeholder="用户名（admin 或 dba）"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          onPressEnter={save}
        />
        <Input.Password
          placeholder="密码"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onPressEnter={save}
        />
      </Space>
    </Modal>
  )
}

export default function App() {
  const location = useLocation()
  const [health, setHealth] = useState(null)
  const [credOpen, setCredOpen] = useState(false)
  const [currentUser, setCurrentUser] = useState(auth.user())

  // 顶栏探活：后端挂了/写接口急停，一进页面就能看出来，
  // 不用等点了保存才发现 403（Ticket 13 任务 5 的联调前置就靠这个可见）
  useEffect(() => {
    api
      .healthMetadata()
      .then(setHealth)
      .catch(() => setHealth({ error: true }))
  }, [])

  // 任何写请求 401 都自动把凭证框弹出来：让用户「知道该干什么」，
  // 而不是盯着「HTTP 401」猜。403 不弹 —— 那是角色不够，弹了也没用。
  useEffect(() => {
    const onUnauthorized = () => {
      setCredOpen(true)
      message.warning('这个操作需要写入凭证，请填写后再试')
    }
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  // 经营大屏是全屏看板：不套 Header/Sider，否则投到大屏上白边和菜单都很碍眼。
  // 这里早退放在所有 hooks 之后，hooks 调用顺序不变。
  if (location.pathname.startsWith('/dashboard')) {
    return <DashboardPage />
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          background: '#fff',
          borderBottom: '1px solid #e5e6eb',
          paddingInline: 20,
        }}
      >
        <Typography.Text strong style={{ fontSize: 16 }}>
          DataPilot 数据领航员
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          语义层约束 + 五道闸门 · 只读账号 · 不信裸 SQL
        </Typography.Text>
        <div style={{ flex: 1 }} />
        <Space size={6}>
          {health && !health.error ? (
            <>
              <Tag color="green">后端在线</Tag>
              <Tag>指标 {health.metricCount}</Tag>
              <Tag>字段 {health.fieldCount}</Tag>
              <Tag>闸门参数 {health.gateConfigCount}</Tag>
              {health.writeEnabled ? (
                <Tag color="blue">写入可用</Tag>
              ) : (
                <Tag color="orange">写入急停中</Tag>
              )}
            </>
          ) : (
            <Tag color="red">后端不可达</Tag>
          )}
          {currentUser ? (
            <Tag color="green" icon={<KeyOutlined />} closable onClose={() => {
              auth.clear()
              setCurrentUser('')
              message.info('已清除写入凭证，写操作会重新要求凭证')
            }}>
              {currentUser}
            </Tag>
          ) : (
            <Button size="small" icon={<KeyOutlined />} onClick={() => setCredOpen(true)}>
              写入凭证
            </Button>
          )}
        </Space>
      </Header>
      <Layout>
        <Sider width={200} style={{ background: '#fff', borderRight: '1px solid #e5e6eb' }}>
          <Menu mode="inline" selectedKeys={[location.pathname]} items={MENU} style={{ borderInlineEnd: 'none' }} />
        </Sider>
        <Content style={{ padding: 20 }}>
          <Routes>
            <Route path="/" element={<Navigate to="/ask" replace />} />
            <Route path="/ask" element={<AskPage />} />
            <Route path="/metrics" element={<MetricsPage />} />
            <Route path="/metadata" element={<MetadataPage />} />
            <Route path="/gate-config" element={<GateConfigPage />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="*" element={<Navigate to="/ask" replace />} />
          </Routes>
        </Content>
      </Layout>
      <CredentialModal open={credOpen} onClose={() => setCredOpen(false)} onSaved={() => setCurrentUser(auth.user())} />
    </Layout>
  )
}

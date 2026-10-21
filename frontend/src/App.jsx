import { useEffect, useState } from 'react'
import { Layout, Menu, Tag, Space, Typography } from 'antd'
import { MessageOutlined, ExperimentOutlined, DatabaseOutlined, SlidersOutlined } from '@ant-design/icons'
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import AskPage from './pages/AskPage.jsx'
import MetricsPage from './pages/MetricsPage.jsx'
import MetadataPage from './pages/MetadataPage.jsx'
import GateConfigPage from './pages/GateConfigPage.jsx'
import { api } from './api.js'

const { Header, Sider, Content } = Layout

const MENU = [
  { key: '/ask', icon: <MessageOutlined />, label: <Link to="/ask">问数</Link> },
  { key: '/metrics', icon: <ExperimentOutlined />, label: <Link to="/metrics">指标管理</Link> },
  { key: '/metadata', icon: <DatabaseOutlined />, label: <Link to="/metadata">元数据 / 白名单</Link> },
  { key: '/gate-config', icon: <SlidersOutlined />, label: <Link to="/gate-config">闸门配置</Link> },
]

export default function App() {
  const location = useLocation()
  const [health, setHealth] = useState(null)

  // 顶栏探活：后端挂了/写接口关了，一进页面就能看出来，
  // 不用等点了保存才发现 403（Ticket 13 任务 5 的联调前置就靠这个可见）
  useEffect(() => {
    api
      .healthMetadata()
      .then(setHealth)
      .catch(() => setHealth({ error: true }))
  }, [])

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
              <Tag color={health.writeEnabled ? 'blue' : 'orange'}>
                {health.writeEnabled ? '写接口已开' : '写接口关闭'}
              </Tag>
            </>
          ) : (
            <Tag color="red">后端不可达</Tag>
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
            <Route path="*" element={<Navigate to="/ask" replace />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  )
}

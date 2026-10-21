/**
 * 后端接口封装（Ticket 13）。
 *
 * 约定：路径一律用相对路径 `/api/...`。开发期由 vite proxy 反代到 18080，
 * 上线由 Nginx 反代，页面本身不知道后端在哪 —— 这样配置只存在于构建/部署层，
 * 也天然同源、不需要 CORS。
 */

async function request(path, { method = 'GET', body } = {}) {
  const resp = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  const text = await resp.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = { error: text }
    }
  }

  if (!resp.ok) {
    // 后端把业务原因放在 { error: "..." }，直接透给用户看，
    // 不要在前端改写成「请求失败」—— 那句话对排查毫无价值
    const reason = (data && (data.error || data.message)) || `HTTP ${resp.status}`
    const err = new Error(reason)
    err.status = resp.status
    throw err
  }
  return data
}

export const api = {
  // ---- 问数主链路 ----
  ask: (question, sessionId) => request('/api/ask', { method: 'POST', body: { question, sessionId } }),

  // ---- HITL 两段（Ticket 12）----
  preview: (question, sessionId) => request('/api/ask/preview', { method: 'POST', body: { question, sessionId } }),
  execute: (previewId, ir) => request('/api/ask/execute', { method: 'POST', body: { previewId, ir } }),

  // ---- 指标 ----
  metrics: () => request('/api/metrics'),
  putMetric: (m) => request('/api/metrics', { method: 'PUT', body: m }),

  // ---- 元数据 / 字段白名单 ----
  tables: () => request('/api/metadata/tables'),
  fields: () => request('/api/metadata/fields'),
  putField: (f) => request('/api/metadata/fields', { method: 'PUT', body: f }),
  deleteField: (tableName, fieldName) =>
    request(
      `/api/metadata/fields?tableName=${encodeURIComponent(tableName)}&fieldName=${encodeURIComponent(fieldName)}`,
      { method: 'DELETE' },
    ),

  // ---- 闸门阈值 ----
  gateConfigs: () => request('/api/gate-config'),
  putGateConfig: (g) => request('/api/gate-config', { method: 'PUT', body: g }),

  // ---- 运维/链路 ----
  healthMetadata: () => request('/api/health/metadata'),
  traces: (limit = 20, sessionId) =>
    request(`/api/traces?limit=${limit}${sessionId ? `&sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  traceDetail: (traceId) => request(`/api/traces/${encodeURIComponent(traceId)}`),
}

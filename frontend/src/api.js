/**
 * 后端接口封装（Ticket 13；凭证 Ticket 14）。
 *
 * 约定：路径一律用相对路径 `/api/...`。开发期由 vite proxy 反代到 18080，
 * 上线由 Nginx 反代，页面本身不知道后端在哪 —— 这样配置只存在于构建/部署层，
 * 也天然同源、不需要 CORS。
 *
 * <h3>写接口的凭证（Ticket 14）</h3>
 * 后端只保护写接口：指标、闸门阈值、字段白名单。读和问数仍然匿名。
 * 凭证由用户在页头「写入凭证」里填，存 sessionStorage，每个请求以
 * `Authorization: Basic ...` 显式带上 —— <b>不是 Cookie</b>，所以后端关掉了 CSRF，
 * 也不存在「关掉页面还留着登录态」这回事。
 *
 * <p>要说清的一点：sessionStorage 里存的是 base64（不是加密），任何能读到
 * 这个标签页的人都能拿到凭证。单机作品接受这个代价；真实系统必须走服务端会话
 * 或短期 token，而那是 Spec §6 分期的事，不在本票范围。
 */

const STORAGE_KEY = 'dp.basic'

/** 用户名 + 密码 → Authorization 头（UTF-8 安全；btoa 只吃 latin1） */
function basicToken(username, password) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let bin = ''
  bytes.forEach((b) => {
    bin += String.fromCharCode(b)
  })
  return `Basic ${btoa(bin)}`
}

let credential = sessionStorage.getItem(STORAGE_KEY) || ''
let credentialUser = sessionStorage.getItem(`${STORAGE_KEY}.user`) || ''

export const auth = {
  /** 当前凭证的用户名（空串 = 没设凭证，写操作会 401） */
  user: () => credentialUser,
  has: () => !!credential,
  set: (username, password) => {
    credential = basicToken(username, password)
    credentialUser = username
    sessionStorage.setItem(STORAGE_KEY, credential)
    sessionStorage.setItem(`${STORAGE_KEY}.user`, username)
  },
  clear: () => {
    credential = ''
    credentialUser = ''
    sessionStorage.removeItem(STORAGE_KEY)
    sessionStorage.removeItem(`${STORAGE_KEY}.user`)
  },
}

/**
 * 401 的广播（Ticket 14）。
 *
 * <p>为什么用事件而不是让每个页面各自处理：写接口有 3 个页面共 5 个入口，
 * 各自弹一次「请先设置凭证」会有 5 份文案、5 个容易漏的地方；这里统一广播，
 * App 里挂一个监听器负责弹窗，页面只管照常 catch。
 */
export const UNAUTHORIZED_EVENT = 'dp:unauthorized'

async function request(path, { method = 'GET', body } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  // 读接口也带上凭证（无害）：万一以后收紧读权限，这里不用再改一次
  if (credential) headers.Authorization = credential

  const resp = await fetch(path, {
    method,
    headers,
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
    // 401 = 没凭证/凭证错；403 = 有身份但角色不够。前者用户自己能解决（去填凭证），
    // 所以要主动弹窗；后者必须换个账号，光弹窗没用，让页面把后端的文案显示出来就行。
    if (resp.status === 401) {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: { path, reason } }))
    }
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
  /** mode=create：已存在则后端 409（不覆盖）；mode=upsert：覆盖 */
  putField: (f, mode = 'upsert') =>
    request(`/api/metadata/fields?mode=${encodeURIComponent(mode)}`, { method: 'PUT', body: f }),
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

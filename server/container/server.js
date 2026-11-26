// dsh-mobile-tunnel 三合一容器：域名改写代理 + Cloudflare 快速隧道 + DNSHE 固定域名同步
//
// 为什么需要域名改写：DSH 的 API 准入栅栏只放行三种 Host——本机回环、监听地址自身、
// 启动参数写死的信任域名。手机经隧道进来时报的 Host 是隧道域名（每次重启都变），
// 没法进信任名单；本代理把 Host/Origin 统一改写成 DSH 的监听地址（准入栅栏认可），
// 让请求过栅栏。认证不在这里做——由 DSH 的登录插件（/login 账号密码 → 官方 Cookie）承担。
import { spawn } from 'node:child_process'
import { createServer, request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { connect as netConnect } from 'node:net'
import { connect as tlsConnect } from 'node:tls'

const env = process.env
const LISTEN_PORT = Number(env.LISTEN_PORT || 8082)
if (!env.UPSTREAM) {
  console.error('[tunnel-box] 缺少 UPSTREAM 环境变量（DSH web 的监听地址，如 https://<DSH容器IP>:3080）')
  process.exit(1)
}
const UPSTREAM = new URL(env.UPSTREAM)
const TUNNEL_ENABLED = env.TUNNEL_ENABLED !== '0'
const DOMAIN = env.DNSHE_DOMAIN || ''
const SUBDOMAIN_ID = env.DNSHE_SUBDOMAIN_ID || ''
const DNSHE_KEY = env.DNSHE_KEY || ''
const DNSHE_SECRET = env.DNSHE_SECRET || ''
const DNSHE_API =
  env.DNSHE_API || 'https://api005.dnshe.com/index.php?m=domain_hub&endpoint=dns_records'
const TUNNEL_HOST_PATTERN = /https:\/\/([a-z0-9-]+\.trycloudflare\.com)/
const TUNNEL_BACKOFF_MS = Number(env.TUNNEL_BACKOFF_MS || 5000)

const log = (...args) => console.log(new Date().toISOString(), '[tunnel-box]', ...args)

const UPSTREAM_HOST = UPSTREAM.host // hostname[:port]
const UPSTREAM_TLS = UPSTREAM.protocol === 'https:'
const UPSTREAM_PORT = Number(UPSTREAM.port) || (UPSTREAM_TLS ? 443 : 80)

/** 向上游发一次请求：按上游协议选模块；上游是自签 TLS 时关闭证书校验（仅此一跳）。 */
function requestUpstream(options, callback) {
  const merged = { ...options, servername: UPSTREAM.hostname }
  if (UPSTREAM_TLS) {
    merged.rejectUnauthorized = false
    return httpsRequest(merged, callback)
  }
  return httpRequest(merged, callback)
}

/** 改写后的转发头：Host/Origin 统一成上游监听地址（准入栅栏认可的权威）。 */
function rewriteHeaders(headers) {
  const out = { ...headers, host: UPSTREAM_HOST }
  if (out.origin) out.origin = `${UPSTREAM.protocol}//${UPSTREAM_HOST}`
  return out
}

// ── 1) 域名改写代理：0.0.0.0:LISTEN_PORT → UPSTREAM ────────────────────────────
// 请求与响应全部流式透传（gzip/SSE/大文件由 DSH 自带的压缩中间件处理）。
// 不设上游空闲超时——SSE/长流会被超时打断；只透传客户端中断防上游 socket 泄漏。
const server = createServer((req, res) => {
  const headers = rewriteHeaders(req.headers)
  const proxy = requestUpstream(
    { host: UPSTREAM.hostname, port: UPSTREAM_PORT, path: req.url, method: req.method, headers },
    (upRes) => {
      res.writeHead(upRes.statusCode ?? 502, upRes.headers)
      upRes.pipe(res)
      upRes.on('error', () => res.destroy())
    }
  )
  proxy.on('error', (e) => {
    if (!res.headersSent) {
      res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
      res.end('tunnel-box proxy error: ' + e.message)
    } else {
      res.destroy()
    }
  })
  req.on('aborted', () => proxy.destroy())
  req.pipe(proxy)
})

// ── 2) WebSocket 升级透传：只放行 DSH 的 API 通道（/api/*，含 /api/remote.mux）──
// 上游是 TLS 时改用 TLS 套接字裸传握手与双向流。
server.on('upgrade', (req, socket, head) => {
  if (!/^\/api(\/|$)/.test(req.url || '')) {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n')
    socket.destroy()
    return
  }
  const headers = rewriteHeaders(req.headers)
  const connectUpstream = UPSTREAM_TLS
    ? (cb) =>
        tlsConnect(UPSTREAM_PORT, UPSTREAM.hostname, { rejectUnauthorized: false, servername: UPSTREAM.hostname }, cb)
    : (cb) => netConnect(UPSTREAM_PORT, UPSTREAM.hostname, cb)
  const upstream = connectUpstream(() => {
    const headLines = [
      `${req.method} ${req.url} HTTP/1.1`,
      ...Object.entries(headers).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : v}`),
      '',
      '',
    ].join('\r\n')
    upstream.write(headLines)
    if (head?.length) upstream.write(head)
    socket.pipe(upstream)
    upstream.pipe(socket)
  })
  const teardown = () => {
    socket.destroy()
    upstream.destroy()
  }
  upstream.on('error', teardown)
  socket.on('error', teardown)
  socket.on('close', teardown)
  upstream.on('close', teardown)
})

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') log(`端口 ${LISTEN_PORT} 被占用，容器退出重建`)
  else log('代理错误: ' + e.message)
  process.exit(1)
})

server.listen(LISTEN_PORT, '0.0.0.0', () => {
  log(`改写代理已启动：0.0.0.0:${LISTEN_PORT} → ${UPSTREAM.protocol}//${UPSTREAM_HOST}（认证由 DSH 登录插件承担）`)
})

// ── 3) DNSHE 固定域名同步 ──────────────────────────────────────────────────────
async function dnshe(action, body) {
  const res = await fetch(`${DNSHE_API}&action=${action}`, {
    method: body ? 'POST' : 'GET',
    headers: { 'X-API-Key': DNSHE_KEY, 'X-API-Secret': DNSHE_SECRET, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  return res.json()
}

async function syncDnshe(cname) {
  if (!DOMAIN || !SUBDOMAIN_ID || !DNSHE_KEY || !DNSHE_SECRET) {
    log('DNSHE 配置不全（DNSHE_DOMAIN / DNSHE_SUBDOMAIN_ID / DNSHE_KEY / DNSHE_SECRET），跳过域名同步')
    return true
  }
  try {
    const list = await dnshe('list', null).then((j) => j, (e) => ({ success: false, message: [String(e)] }))
    if (!list?.success) {
      log('DNSHE 记录列表获取失败: ' + JSON.stringify(list?.message ?? list))
      return false
    }
    // 先建新 CNAME、成功后再删旧记录（旧内容不等于新指身的都算旧）——
    // 避免「删光了、新建失败」导致固定域名裸奔在零记录状态
    const create = await dnshe('create', { subdomain_id: Number(SUBDOMAIN_ID), type: 'CNAME', content: cname })
    if (!create?.success) {
      log('固定域名创建失败: ' + JSON.stringify(create?.message ?? create) + '（旧记录保留，域名仍指旧地）')
      return false
    }
    log(`固定域名创建成功：${DOMAIN} → CNAME ${cname}`)
    for (const r of list.records ?? []) {
      if (r.content === cname) continue // 新建的那条不动
      // 非隧道的 TXT 记录（域名验证类）保留，防误删；其余旧记录（全部旧 CNAME、
      // 含 trycloudflare 的旧 TXT 信标）重置——这是文档声明的「占用即重置」行为
      const type = String(r.type ?? '').toUpperCase()
      const isTxt = type === 'TXT' || type === '16'
      const content = String(r.content ?? '')
      if (isTxt && !content.includes('trycloudflare.com')) continue
      const del = await dnshe('delete', { record_id: r.record_id })
      if (del?.success) log(`已删除旧记录 ${r.type} ${content}`)
      else log('删除旧记录失败: ' + JSON.stringify(del?.message ?? del))
    }
    return true
  } catch (e) {
    log('DNSHE 同步异常: ' + (e?.message ?? e))
    return false
  }
}

/** 稳态同步：失败按 30 秒退避重试到成功为止；隧道域名变更后旧循环自动让位。 */
let steadyGen = 0
async function syncDnsheSteady(cname) {
  const gen = ++steadyGen
  while (gen === steadyGen) {
    const ok = await syncDnshe(cname)
    if (ok || gen !== steadyGen) return
    await new Promise((r) => setTimeout(r, 30_000))
  }
}

// ── 4) cloudflared 快速隧道：起进程、抓域名、变更即同步、退出即重拉 ──────────────
let tunnelProc = null
let lastDomain = ''

// 单一重试调度：'error' 与 'exit' 可能先后都触发，只排一次，防止拉起双进程
let retryScheduled = false
function scheduleTunnelRetry(delay) {
  if (retryScheduled) return
  retryScheduled = true
  setTimeout(() => {
    retryScheduled = false
    startTunnel()
  }, delay)
}

function startTunnel() {
  if (!TUNNEL_ENABLED) {
    log('TUNNEL_ENABLED=0，隧道停用（仅改写代理在跑）')
    return
  }
  if (tunnelProc && tunnelProc.exitCode === null && !tunnelProc.killed) {
    tunnelProc.kill() // 防御性清场：任何残留的旧隧道进程先退场
  }
  log('启动 cloudflared 快速隧道…')
  tunnelProc = spawn('cloudflared', ['tunnel', '--url', `http://127.0.0.1:${LISTEN_PORT}`, '--protocol', 'http2'], {
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  // cloudflared 缺失等 spawn 失败只触发 'error'、可能没有 'exit'——同样安排退避重拉
  tunnelProc.on('error', (e) => {
    log(`隧道进程启动失败（${e.code ?? e.message}）；安装 cloudflared 或设 TUNNEL_ENABLED=0 可停用隧道`)
    scheduleTunnelRetry(TUNNEL_BACKOFF_MS * 2)
  })
  // trycloudflare 的域名打在 stderr；按行缓冲后再正则（URL 可能被分块截断）
  let stderrLine = ''
  tunnelProc.stderr.on('data', (buf) => {
    stderrLine += buf.toString()
    let nl
    while ((nl = stderrLine.indexOf('\n')) >= 0) {
      const line = stderrLine.slice(0, nl)
      stderrLine = stderrLine.slice(nl + 1)
      const m = line.match(TUNNEL_HOST_PATTERN)
      if (m && m[1] !== lastDomain) {
        lastDomain = m[1]
        log(`隧道就绪：https://${lastDomain}（同步到固定域名 ${DOMAIN || '未配置'}）`)
        void syncDnsheSteady(lastDomain)
      }
    }
  })
  tunnelProc.on('exit', (code) => {
    log(`隧道进程退出（code ${code}），${TUNNEL_BACKOFF_MS}ms 后重拉`)
    scheduleTunnelRetry(TUNNEL_BACKOFF_MS)
  })
}

startTunnel()

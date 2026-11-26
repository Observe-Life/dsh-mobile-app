# 更新日志

## [2026-11-26] v2.0 —— 个人版大改（适配 DSH 0.2.2-rc.5）

### 新增
- 双通道自动选：家庭 WiFi 直连优先，不通自动经固定域名 DoH 反查 Cloudflare 隧道
  （反查只用阿里 DoH，用户裁决）
- 登录完全交给 DSH 登录插件：App 不存任何账号密码（用户裁决，拆除自动登录与
  凭据存储），首次与每 30 天在插件页面手登一次，Cookie 在 WebView 内持久化
- 文件上传：实现 WebView 文件选择桥接——拍照（FileProvider）/ 相册 / 文件
- 文件下载：系统下载管理器 + 网页 blob 下载 JS 桥接，落盘「下载」目录
- 服务器端三合一容器 `server/container/`：域名改写代理 + Cloudflare 快速隧道 +
  DNSHE 固定域名同步（适配飞牛OS Docker 部署）
- CI 固定签名：`KEYSTORE_BASE64` / `KEYSTORE_PASS` 仓库 Secret，升级可覆盖安装

### 移除（适配新 DSH 认证体系）
- Basic Auth 认证代理插件 `server/plugin/`（DSH 已自带登录认证，代理的密码边界
  职能由登录插件承担；其域名改写职能由三合一容器的代理继承）
- Windows 专用脚本 `server/tools/`（PowerShell 启动/看门狗/isLoopback 补丁——
  服务器改用容器部署；isLoopback 补丁在新版已无需）
- 移动排版 CSS 适配层 `mobile-inject.css`（当前 DSH 前端手机视口自适应，实测
  无溢出，适配层 0 命中已失效）
- 旧文档 `docs/QUICKSTART.md`、`docs/BOUNDARIES.md`（内容并入 README/DEPLOY）

### 归档
- `pwa/` 通用客户端原样保留：旧认证体系专用，新体系下跨站 iframe Cookie 受限，
  重新启用需改造；跨设备免安装现推荐直接用 DSH 网页自带的 PWA manifest

## [2026-08-16] 旧版（原上游项目）

- PWA 通用客户端、Android 壳应用、Basic Auth 代理插件、Windows 一键启动脚本，
  详见原上游仓库说明。

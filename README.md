# DSH Remote —— Android 手机端（个人版）

DeepSeek Harness 的个人安卓客户端：全屏 WebView 加载你自己服务器上的 DSH 网页，
两条通道自动选——**家里 WiFi 直连（快），在外面自动走 Cloudflare 快速隧道（公网）**。
认证由服务器上的 DSH 登录插件承担：App 不存账号密码，首次与每 30 天在插件
登录页手填一次。

## 原理

```
                ┌─ 家里 WiFi：手机 ──→ https://家庭地址:3080  DSH web（HTTPS + 登录插件）
DSH Remote App ─┤
                └─ 在外面：手机 → 隧道域名（DNS 反查自动发现）
                        → Cloudflare 快速隧道
                        → dsh-mobile-tunnel 容器（域名改写，过 DSH 准入栅栏）
                        → DSH web
```

- **双通道自动选**：先探测家庭直连地址，不通自动用固定域名（如 `xxx.de5.net`）
  经阿里 DoH 反查当前隧道地址；
- **登录交给服务器插件**：App 不存任何账号密码；首次与每 30 天在插件的
  `/login` 页面手填一次，Cookie 在手机浏览器内核里持久化；
- **文件上传**：DSH 页面的上传按钮可用——拍照 / 相册 / 文件；
- **文件下载**：收到的成品文件一键存入系统「下载」目录（网页 blob 下载已桥接）；
- **实时通道**：审批弹窗、实时事件走 WebSocket（`/api/remote.mux`），WebView 原生支持；
- **固定签名**：CI 用仓库 Secret 里的固定 keystore 签名，升级直接覆盖安装。

## 构建（GitHub Actions 在线构建，本机零安装）

1. 打开本仓库 **Actions** → 手动运行 `Build APK` 工作流（约 3~5 分钟）；
2. 构建完成后在该次运行的 **Summary** 页下载 `dsh-mobile-apk` 工件里的
   `app-release.apk`（或到 Releases 页下载带版本号的包）；
3. APK 传到手机安装（允许「安装未知来源应用」）。

> 签名：`KEYSTORE_BASE64` / `KEYSTORE_PASS` 两个仓库 Secret 提供固定签名密钥
> （PKCS12 格式，别名固定 `dsh`——自带 keystore 的话请用同名别名，或改工作流）；
> 缺 Secret 时 CI 退回一次性临时签名——那种包只能全新安装，不能覆盖升级。

## 使用

1. 手机上打开 App → 首次启动自动弹出设置；
2. 填两项：
   - **家庭直连地址**：如 `https://192.168.1.10:3080`（你家服务器的内网地址，可留空）；
   - **固定域名**：如 `your-domain.de5.net`（公网通道门牌，可留空）；
3. 保存即连接。在家走直连、出门走隧道，全自动；
4. 首次连接会跳到登录页，填一次 DSH 插件的账号密码（30 天内不用再登）。

## 服务器端

手机公网可达依赖服务器上的 **dsh-mobile-tunnel 三合一容器**
（域名改写代理 + Cloudflare 快速隧道 + DNSHE 固定域名同步），部署见
[DEPLOY.md](./DEPLOY.md)。认证不靠它——它只负责把隧道流量「改写地名」送过
DSH 的准入栅栏；认证完全由 DSH 登录插件（账号密码 → 官方 Cookie）承担。

## 文档导航

| 文档 | 内容 |
|---|---|
| [DEPLOY.md](./DEPLOY.md) | 服务器端容器部署指南 |
| [server/container/](./server/container/) | 三合一容器源码与说明 |
| [docs/PWA-INSTALL.md](./docs/PWA-INSTALL.md) | 跨设备免安装方案（DSH 网页自带 PWA） |
| [pwa/](./pwa/) | 旧 PWA 客户端（已归档，新体系下需改造才可用） |
| [CHANGELOG.md](./CHANGELOG.md) | 更新日志 |
| [LICENSE](./LICENSE) | MIT 许可证 |

## 安全须知

- 连接全链路 HTTPS；自签证书的 SSL 放行**仅限**你配置的家庭直连地址这一处；
- 公网入口的唯一锁是登录插件的账号密码——**请设强密码**；密码即边界；
- DNSHE 的 API 密钥、登录凭据只存服务器 `.env` 与手机加密存储，不进任何 git 仓库；
- 已知限制：系统下载管理器过不了自签证书的校验，**家庭直连通道**里直接的
  http(s) 链接下载可能失败——网页内的 blob 下载通道（已桥接）与公网隧道通道
  不受影响；
- 免费快速隧道带宽有限，拉取超大会话会稍慢，属正常现象。

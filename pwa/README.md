# DSH Remote PWA（通用客户端）

> **⚠️ 归档说明（2026-11）**：本目录原样保留、不再维护。它是为旧的「Basic Auth
> 认证代理」体系写的：凭弹窗输密码 + iframe 内嵌。新体系（DSH 登录插件 +
> 域名改写代理容器）下，跨站 iframe 的 Cookie 会被现代浏览器拦住，本页面的
> 连接方式已不可用。当前跨设备免安装的推荐做法：手机/电脑浏览器直接打开隧道
> 地址 → 登录 → 「添加到主屏幕」——DSH 官方网页自带 PWA manifest，可直接安装。
> 重新启用本目录前需按新认证体系改造登录流程。

这是一个**零后端、零成本**的 PWA：
- 用户打开固定网址
- 填写自己的 DSH 服务器地址 / 用户名 / 密码
- 连接自己的 DSH

## 部署到 GitHub Pages

1. 把本仓库推到 GitHub
2. 仓库 `Settings -> Pages`
3. Source 选择：
   - `Deploy from a branch`
   - Branch: `main`
   - Folder: `/`（或 `/pwa` 如果只发布 PWA）
4. 保存后访问：
   ```
   https://<你的用户名>.github.io/<仓库名>/pwa/
   ```
   例如：
   ```
   https://hongshuxifan321.github.io/dsh-mobile-app/pwa/
   ```

> ✅ 已启用 GitHub Pages，当前可直接访问：
> https://hongshuxifan321.github.io/dsh-mobile-app/pwa/

## 使用

1. 打开 PWA 地址
2. 点“设置”
3. 填：
   - 服务器地址：`https://你的DSH地址/mobile`
   - 用户名 / 密码
4. 保存并连接
5. 如果浏览器弹出登录框，输入用户名/密码即可

## 说明

- 密码仅保存在当前页面内存中，关闭页面后不会持久化，更安全
- 首次连接通过弹窗完成 Basic Auth，浏览器会缓存该域名的登录凭证
- 之后 iframe 内可直接加载 DSH 界面

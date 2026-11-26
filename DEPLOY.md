# 部署指南（服务器端）

本指南面向**你自己的一台飞牛OS/任意 Docker 服务器**：把「手机公网可达」的最小
服务端（dsh-mobile-tunnel 三合一容器）跑起来。预计耗时 10 分钟。

## 前提

- 服务器上已运行 DSH web（容器化，HTTPS + 登录插件；记下它的监听地址，
  形如 `https://<DSH容器IP>:3080`）；
- 服务器与手机在同一个 backend 自建网桥（容器间按容器名互通）；
- 一个 DNSHE 免费域名（控制台能拿到：固定域名、子域名 ID、API 密钥、API Secret）。

## 部署步骤

1. 在服务器上建部署目录：

   ```bash
   mkdir -p /vol1/docker/dsh-mobile-tunnel && cd $_
   ```

2. 把本仓库 `server/container/` 下的 `Dockerfile`、`server.js`、
   `docker-compose.example.yml` 传上去；样例 compose 改名为 `docker-compose.yml`；

3. 同目录建 `.env`，填 DNSHE 四件套（**真实密钥不进 git**）：

   ```
   DNSHE_DOMAIN=your-domain.de5.net
   DNSHE_SUBDOMAIN_ID=xxxx
   DNSHE_KEY=cfsd_xxxx
   DNSHE_SECRET=xxxx
   ```

4. 启动：

   ```bash
   docker compose up -d --build
   ```

5. 看日志确认三件事（容器日志）：

   ```
   [tunnel-box] 改写代理已启动：0.0.0.0:8082 → https://<DSH容器IP>:3080
   [tunnel-box] 隧道就绪：https://xxxx.trycloudflare.com
   [tunnel-box] 固定域名同步成功：your-domain.de5.net → CNAME xxxx.trycloudflare.com
   ```

之后手机 App 填固定域名 `your-domain.de5.net` 即可公网连接；隧道地址每次重启
自动重新同步，手机无需改任何配置。

## 它做了什么（三合一）

| 组件 | 职责 |
|---|---|
| 域名改写代理 | 把隧道流量（Host/Origin 是隧道域名）改写成 DSH 监听地址——DSH 的 API 准入栅栏只认本机回环、监听地址、启动参数信任域名，隧道域名每次重启都变，进不了信任名单 |
| cloudflared 快速隧道 | 免费把代理端口暴露公网，网址每次重启变化 |
| DNSHE 同步 | 隧道域名一变，自动把固定域名 CNAME 到最新隧道地址 |

认证不在这里：账号密码 → 官方 Cookie 由 **DSH 登录插件**承担，代理只改域名、
不设密码边界。

## 故障排查

| 现象 | 处理 |
|---|---|
| App「隧道发现失败」 | 服务器上容器是否在跑（`docker ps`）？固定域名 DNS 是否已同步（`nslookup -type=CNAME your-domain.de5.net`）？ |
| 公网打开转圈 | 云flare 隧道断开会在数秒内自动重拉并重新同步域名；稍等 1~2 分钟 |
| 登录后进不去 | 登录插件账号密码是否改过？在登录页手登一次即可（Cookie 30 天） |
| 首次构建慢 | 容器首次 `--build` 要拉 node 与 cloudflared 镜像，之后秒级 |

## 资源重置

若这个 DNSHE 域名此前被别的荒废方案占用（旧 CNAME/记录），不用改域名——
容器启动同步时会**先删光该子域名下全部旧记录再建 CNAME**，自动重置归本项目用。

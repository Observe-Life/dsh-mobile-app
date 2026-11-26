# dsh-mobile-tunnel（服务器端三合一容器）

给 DSH web 补上「手机公网可达」的最小服务端，三个职责一个容器：

1. **域名改写代理**：把手机经隧道发来的 Host/Origin 统一改写成 DSH 的监听地址
   （DSH 的 API 准入栅栏只认本机回环、监听地址自身、启动参数信任域名三种）；
2. **Cloudflare 快速隧道**：`cloudflared tunnel`，把代理端口暴露到公网（免费、免备案，
   网址每次重启会变）；
3. **DNSHE 固定域名同步**：隧道域名一变，自动把你的 DNSHE 免费固定域名 CNAME 到
   最新隧道地址——手机永远只记固定域名。

认证不做在这里：由 DSH 的登录插件（`/login` 账号密码 → 官方签名 Cookie）承担，
代理过栅栏只改域名、不设密码边界。

## 部署（飞牛OS Docker）

```bash
mkdir -p /vol1/docker/dsh-mobile-tunnel && cd $_
# 放入 docker-compose.yml（由样例复制）与 .env
docker compose up -d --build
```

`.env` 四个键：`DNSHE_DOMAIN`（固定域名）、`DNSHE_SUBDOMAIN_ID`、`DNSHE_KEY`、
`DNSHE_SECRET`（DNSHE 控制台 API 管理里创建）。真实密钥只存服务器 `.env`，不进 git。

启动成功的标志（容器日志）：`改写代理已启动` + `固定域名同步成功`。

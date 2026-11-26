FROM cloudflare/cloudflared:latest AS cloudflared
FROM node:22-alpine
COPY --from=cloudflared /usr/local/bin/cloudflared /usr/local/bin/cloudflared
WORKDIR /app
COPY server.js .
ENV NODE_ENV=production
CMD ["node", "server.js"]

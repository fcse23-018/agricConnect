# AgriConnect API — production image
# Multi-stage: install production dependencies, then copy only what is needed.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev

FROM node:22-alpine AS runner
ENV NODE_ENV=production
WORKDIR /app

# Run as a non-root user.
RUN addgroup -S agri && adduser -S agri -G agri

COPY --from=deps /app/node_modules ./node_modules
COPY package*.json ./
COPY migrations ./migrations
COPY src ./src

USER agri
EXPOSE 4000

# Liveness probe; the /ready endpoint additionally checks the database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||4000)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "src/server.js"]

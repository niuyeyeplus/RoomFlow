# syntax=docker/dockerfile:1
FROM node:22-alpine AS builder

WORKDIR /app

# Install dependencies first to leverage cache
COPY frontend/package*.json ./
RUN npm ci

# Copy the rest of the frontend source and build
COPY frontend/. .
RUN npm run build

FROM nginx:stable-alpine AS runner

# Copy built static assets
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy SPA routing fallback configuration
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]

# Frontend level. Stage 1 builds the static files with Node; stage 2 serves them with nginx.
# The final image has no Node.js and no source code: just HTML, JS, CSS and a web server.

# ---- Stage 1: build ----
FROM node:22-alpine AS build
WORKDIR /app
# Dependencies first, so this slow step is cached until package-lock.json changes.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---- Stage 2: serve ----
# The "unprivileged" variant runs nginx as a normal user, listening on 8080 instead of 80.
FROM nginxinc/nginx-unprivileged:1.30-alpine
# Files in /etc/nginx/templates get ${VARIABLES} filled in from the environment at startup.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
# Where /api requests go: the Spring Boot service (Kubernetes Service "shop", port 80).
ENV API_UPSTREAM=http://shop:80
EXPOSE 8080

# Web estática servida por nginx en Cloud Run (WEB-F01). Reenvía /api/ al BFF para que la web y el
# BFF compartan origen: la cookie de sesión funciona sin CORS. La URL del BFF llega en BFF_URL al
# arrancar, así la misma imagen sirve para staging y producción.

FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Mismo build que el job "web" del CI. Un build de producción nunca incluye los mocks.
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

# Cloud Run inyecta PORT; sin ella, 8080. La imagen base sustituye ${PORT} y ${BFF_URL} en las
# plantillas de /etc/nginx/templates al arrancar (solo variables definidas, no las de nginx).
ENV PORT=8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

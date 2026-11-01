# Etapa de compilación: instala las dependencias y genera los archivos estáticos
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npx ng build

# Etapa de ejecución: nginx sirve la interfaz y reenvía /api al backend
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/messaging-lab-frontend/browser /usr/share/nginx/html
EXPOSE 80

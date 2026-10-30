# Tareas 01 — Base del proyecto

- **Estado:** Aprobado
- **Fecha:** 2026-10-29
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Las tareas están en orden de dependencia. Cada una debe completarse, con su comprobación «Hecho cuando» superada, antes de empezar la siguiente.

## Fase 0 — Repositorio

- [x] **T01 — `.gitignore` y plantilla de puertos**
  Crear el `.gitignore` de la raíz (`infra/.env`, `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, `frontend/.angular/`, IDE y SO) e `infra/.env.example` con `BACKEND_PORT` y `FRONTEND_PORT` comentados.
  *RF:* RF-5, RF-6 (RNF-5)
  *Hecho cuando:* al crear un `infra/.env` de prueba, `git status` no lo muestra, e `infra/.env.example` contiene las dos variables con sus valores por defecto (8080 y 4200).

## Fase 1 — Backend

- [x] **T02 — Esqueleto Spring Boot**
  Crear `backend/` con Maven Wrapper, Java 21, `groupId` `com.messaginglab`, `artifactId` `messaging-lab-backend`, versión `0.1.0`, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-starter-webmvc-test` y la clase `MessagingLabApplication`.
  *RF:* RF-5
  *Hecho cuando:* `cd backend && ./mvnw test` termina con BUILD SUCCESS y `./mvnw spring-boot:run` arranca sin errores.

- [x] **T03 — Contrato `backend/api/api.yml`**
  Escribir el contrato OpenAPI 3.0.3 con `GET /api/status` (`operationId: getStatus`, etiqueta `Status`) y el esquema `StatusResponse` (`status` enumerado `AVAILABLE`, `name` y `version`, todos obligatorios).
  *RF:* RF-1 (CA-1.3)
  *Hecho cuando:* el archivo existe y describe la operación y el esquema tal como indica el plan §3.2. Se valida en T04.

- [x] **T04 — Generación de código a partir del contrato**
  Configurar `openapi-generator-maven-plugin` 7.26.0 con la configuración exacta del plan §3.1 (paquetes `com.messaginglab.api` y `com.messaginglab.api.model`) y comprobar su compatibilidad con la versión de Spring Boot fijada.
  *RF:* RF-1 (CA-1.3)
  *Hecho cuando:* `./mvnw compile` genera `StatusApi` y `StatusResponse` en `target/generated-sources`, y el árbol de dependencias (`./mvnw dependency:tree`) no incluye Swagger ni `jackson-databind-nullable`.

- [x] **T05 — Versión y configuración del backend**
  Añadir el objetivo `build-info` de `spring-boot-maven-plugin` y escribir `application.properties` (`spring.application.name=messaging-lab`, `server.port=${BACKEND_PORT:8080}`, `spring.config.import=optional:file:../infra/.env[.properties]`).
  *RF:* RF-1 (CA-1.2, CA-1.4), RF-5 (CA-5.4)
  *Hecho cuando:* tras `./mvnw compile` existe `target/classes/META-INF/build-info.properties` con `build.version=0.1.0`. Sin `infra/.env`, la aplicación arranca en el 8080; con `BACKEND_PORT=8081` en `infra/.env`, arranca en el 8081.

- [x] **T06 — Dominio y servicio de estado**
  Crear el record `model/ApplicationStatus` y `service/StatusService`, que construye el estado a partir de `BuildProperties`, junto con `StatusServiceTest`.
  *RF:* RF-1 (CA-1.1, CA-1.2), RF-7 (CA-7.1)
  *Hecho cuando:* `StatusServiceTest` pasa y verifica el estado `AVAILABLE`, el nombre y la versión tomados de `BuildProperties`.

- [x] **T07 — Controlador de estado**
  Crear `controller/StatusController`, que implementa `StatusApi` y traduce `ApplicationStatus` a `StatusResponse`, junto con `StatusControllerTest` (MockMvc, capa web, servicio simulado).
  *RF:* RF-1 (CA-1.1), RF-7 (CA-7.1)
  *Hecho cuando:* `StatusControllerTest` pasa y, con la aplicación arrancada, `curl localhost:8080/api/status` devuelve `{"status":"AVAILABLE","name":"messaging-lab-backend","version":"0.1.0"}`.

- [x] **T08 — Prueba de contexto completo**
  Escribir `MessagingLabApplicationTests`: levanta el contexto completo, llama a `/api/status` y comprueba que la versión coincide con la de `BuildProperties`.
  *RF:* RF-1 (CA-1.2, CA-1.4), RF-7 (CA-7.1)
  *Hecho cuando:* `./mvnw test` pasa con las tres clases de prueba del backend.

- [x] **T09 — Dockerfile del backend**
  Crear `backend/Dockerfile` (multietapa: `eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre`, usuario sin privilegios, puerto 8080) y `backend/.dockerignore`.
  *RF:* RF-6
  *Hecho cuando:* `docker build -t ml-backend backend` termina bien y, con `docker run --rm -p 8080:8080 ml-backend`, `curl localhost:8080/api/status` devuelve la versión `0.1.0`.

## Fase 2 — Frontend

- [x] **T10 — Esqueleto Angular 21**
  Generar `frontend/` con Angular CLI 21: standalone, zoneless, CSS, sin SSR, con Vitest como ejecutor de pruebas.
  *RF:* RF-5
  *Hecho cuando:* `cd frontend && npm test` pasa con la prueba inicial y `npx ng build` termina sin errores.

- [x] **T11 — Arranque local con proxy y puertos**
  Crear `scripts/serve.mjs` (carga `../infra/.env` con `process.loadEnvFile` si existe y lanza `ng serve --port ${FRONTEND_PORT:-4200}`) y `proxy.conf.mjs` (`/api` → `http://localhost:${BACKEND_PORT:-8080}`). Registrar el proxy en `angular.json` (`serve.options.proxyConfig`) y hacer que `npm start` ejecute el script.
  *RF:* RF-5 (CA-5.2, CA-5.3, CA-5.4)
  *Hecho cuando:* con el backend arrancado, `npm start` sirve en el 4200 y `curl localhost:4200/api/status` devuelve el JSON del backend. Con `BACKEND_PORT=8081` y `FRONTEND_PORT=4300` en `infra/.env` (y el backend reiniciado), `curl localhost:4300/api/status` también funciona.

- [x] **T12 — Modelo y validación de la respuesta**
  Crear `core/status/backend-status.ts` con el tipo `BackendStatus` y la función `isBackendStatus()`, junto con sus pruebas. La validación es más estricta que el contrato: un `name` o una `version` vacíos se tratan como respuesta no válida.
  *RF:* RF-3 (CA-3.2), RF-7
  *Hecho cuando:* las pruebas pasan y cubren una respuesta válida, una sin `version`, una con `status` desconocido y un valor que no es un objeto.

- [x] **T13 — Servicio `StatusApiService`**
  Implementar `GET /api/status` con `timeout(20000)` y clasificar los errores según el plan §4.3 («no disponible» frente a «no se ha podido obtener»), junto con sus pruebas (`HttpTestingController`, temporizadores simulados).
  *RF:* RF-2 (CA-2.1), RF-3 (CA-3.1, CA-3.2), RF-7 (CA-7.4) (RNF-4)
  *Hecho cuando:* `status-api.service.spec` pasa y cubre éxito, respuesta incompleta, 500, 502, error de red (HTTP 0) y *timeout* a los 20 s.

- [x] **T14 — Vista de estado**
  Crear `features/status/status-page`:
  - una signal con los casos `loading`, `success` y `error`;
  - indicador de carga;
  - datos con «Disponible», nombre y versión;
  - mensaje de error con `role="alert"`;
  - zona con `aria-live="polite"`;
  - `<button>` «Reintentar», deshabilitado durante la carga.
  *RF:* RF-2 (CA-2.1–CA-2.3), RF-3 (CA-3.3–CA-3.6) (RNF-8)
  *Hecho cuando:* el componente compila y `npx ng build` termina sin errores. El comportamiento se verifica en T15.

- [x] **T15 — Pruebas de la vista de estado**
  Escribir `status-page.spec` con el servicio simulado.
  *RF:* RF-7 (CA-7.2, CA-7.3)
  *Hecho cuando:* `npm test` pasa con casos para:
  - carga visible;
  - datos mostrados;
  - mensaje de error mostrado;
  - reintento con éxito que sustituye al error;
  - botón deshabilitado mientras hay una consulta en curso.

- [x] **T16 — Secciones del menú y *shell***
  Crear `layout/nav-sections.ts` con «Estado» y las tres secciones futuras con sus descripciones (spec CA-4.2), y `layout/shell` con:
  - cabecera con el nombre del laboratorio;
  - menú con las secciones futuras marcadas con `aria-disabled="true"`;
  - aviso «Disponible próximamente» al activarlas;
  - `<router-outlet>`.
  *RF:* RF-4 (CA-4.1, CA-4.2, CA-4.3, CA-4.5)
  *Hecho cuando:* el *shell* compila y `npx ng build` termina sin errores. El comportamiento se verifica en T17.

- [x] **T17 — Pruebas del *shell***
  Escribir `shell.spec`.
  *RF:* RF-4, RF-7
  *Hecho cuando:* `npm test` pasa y verifica:
  - la cabecera;
  - las tres secciones deshabilitadas con su descripción;
  - que al activar una se muestra «Disponible próximamente» sin cambiar la URL.

- [x] **T18 — Rutas**
  Configurar `app.routes.ts` (`''` → `status`, `status` → vista de estado, `**` → `status`) y montar el *shell* en `App`, junto con las pruebas de rutas.
  *RF:* RF-2 (CA-2.1), RF-4 (CA-4.4)
  *Hecho cuando:* `npm test` pasa con una prueba que navega a `/no-existe` y acaba en `/status`.

- [x] **T19 — Dockerfile del frontend**
  Crear `frontend/Dockerfile` (multietapa: `node` LTS Alpine → `nginx` Alpine), `frontend/nginx.conf` (`/api/` → `http://backend:8080`, `try_files $uri /index.html`) y `frontend/.dockerignore`.
  *RF:* RF-6
  *Hecho cuando:* `docker build -t ml-frontend frontend` termina bien. La comunicación con el backend se verifica en T20.

## Fase 3 — Infraestructura

- [x] **T20 — Docker Compose**
  Crear `infra/docker-compose.yml`:
  - `backend` con `build: ../backend` y puertos `${BACKEND_PORT:-8080}:8080`;
  - `frontend` con `build: ../frontend`, puertos `${FRONTEND_PORT:-4200}:80` y `depends_on: backend`.
  *RF:* RF-6 (CA-6.1–CA-6.5)
  *Hecho cuando:*
  - con `docker compose -f infra/docker-compose.yml up -d --build`, en `http://localhost:4200` se ve «Disponible», `messaging-lab-backend` y `0.1.0`;
  - con `FRONTEND_PORT=4300` en `infra/.env` funciona en el 4300;
  - `docker compose -f infra/docker-compose.yml down` deja `docker compose -f infra/docker-compose.yml ps` vacío.

## Fase 4 — Documentación

- [ ] **T21 — README**
  Redactar `README.md` en español con:
  - requisitos previos;
  - configuración de puertos (`infra/.env.example` → `infra/.env`) y qué ajustar para que el frontend siga encontrando el backend (CL-9);
  - arranque local por componentes;
  - arranque completo y parada;
  - ejecución de pruebas;
  - cómo comprobar que funciona;
  - puertos por defecto.
  *RF:* RF-8 (CA-8.1–CA-8.5) (RNF-1, RNF-2)
  *Hecho cuando:* el README tiene una sección para cada criterio CA-8.1 a CA-8.5 y todos sus comandos coinciden con los de AGENTS.md y el plan.

- [x] **T22 — `--build` en AGENTS.md** *(aprobada el 2026-10-30; ver plan §7)*
  Añadir `--build` al comando de arranque de la infraestructura en AGENTS.md.
  *RF:* RF-6
  *Hecho cuando:* el comando de AGENTS.md coincide con el del README.

## Fase 5 — Validación

- [ ] **T23 — Pruebas automatizadas completas**
  Ejecutar `cd backend && ./mvnw test` y `cd frontend && npm test` sin el otro componente en marcha.
  *RF:* RF-7 (CA-7.1–CA-7.4)
  *Hecho cuando:* las dos ejecuciones terminan sin fallos y su resultado se ha anotado.

- [ ] **T24 — Verificación manual en local (Chrome)**
  Seguir los pasos 2, 3, 4 y 6 de la verificación del plan (§9) en la última versión estable de Chrome.
  *RF:* RF-2, RF-3, RF-4, RF-5 (RNF-8, RNF-9)
  *Hecho cuando:*
  - se ven el estado, el error y el reintento con éxito;
  - funciona con puertos personalizados;
  - la redirección de rutas desconocidas y las secciones deshabilitadas se comportan según la spec;
  - el botón «Reintentar» se activa con el teclado.

- [ ] **T25 — Verificación desde un clon limpio**
  Clonar el repositorio en un directorio temporal y seguir solo el README para levantar las dos formas de arranque y pararlas.
  *RF:* RF-6, RF-8 (RNF-2); criterios de finalización 2, 3 y 6
  *Hecho cuando:* las dos formas de arranque muestran la misma versión (`0.1.0`) sin pasos que no estén en el README, y se ha comprobado cada criterio de finalización de la spec.

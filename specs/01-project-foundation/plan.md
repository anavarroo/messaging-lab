# Plan 01 — Base del proyecto

- **Estado:** Aprobado
- **Fecha:** 2026-10-29
- **Especificación:** [spec.md](spec.md) (aprobada)

## 1. Resumen

Se crean tres piezas: un backend que expone una única operación de estado, un frontend que la consume y muestra el esqueleto de navegación del laboratorio, y un arranque completo en contenedores. La pieza clave del diseño es que el frontend siempre llama a la ruta relativa `/api`. En desarrollo local reenvía esas llamadas el servidor de desarrollo de Angular; en contenedores lo hace el servidor web que sirve el frontend. Así el código del frontend nunca conoce la dirección del backend, no hace falta CORS y cambiar un puerto solo afecta a la configuración.

```
Navegador ──> Frontend (/, /status, ...) ──/api/*──> Backend (GET /api/status)
                 │
                 ├─ Local: servidor de desarrollo de Angular + proxy
                 └─ Contenedores: nginx (estáticos + proxy)
```

## 2. Decisiones

| # | Decisión | Origen | Motivo |
|---|----------|--------|--------|
| D-1 | Paquete base `com.messaginglab`, organizado por capas (MVC) | Usuario | — |
| D-2 | El código de la API (interfaz y DTO) se genera a partir de `backend/api/api.yml` | Usuario | El contrato y la implementación no pueden desincronizarse: si cambian, no compila (CA-1.3). |
| D-3 | Cada módulo tiene su propio Dockerfile; el Docker Compose va en `infra/docker-compose.yml` | Usuario | Coincide con la estructura y los comandos de AGENTS.md. |
| D-4 | Los puertos se configuran en un único `infra/.env`, ignorado por git, con una plantilla `infra/.env.example` versionada. Si no existe `.env`, se usan valores por defecto | Usuario + propuesta | Un solo archivo para las dos formas de arranque (CA-5.4, CA-6.5). Va en `infra/` porque Docker Compose lee el `.env` de la carpeta del archivo Compose. Así funciona el comando de AGENTS.md sin opciones extra y el `.env` sigue siendo opcional. El backend y el frontend lo leen sin dependencias nuevas (ver §3.3 y §4.4). |
| D-5 | El frontend usa siempre la ruta relativa `/api` y un proxy hace el reenvío en cada forma de arranque | Propuesta | No hay que gestionar URLs por entorno ni CORS. Cumple CA-5.3 y evita el caso límite CL-9. |
| D-6 | El frontend usa componentes standalone, signals y la organización `core/` + `layout/` + `features/` | Propuesta | Es lo idiomático en Angular 21 y escala a las secciones futuras (cada laboratorio será una *feature*). |
| D-7 | El frontend no genera código a partir de `api.yml`: el modelo TypeScript se escribe a mano y se valida en tiempo de ejecución | Propuesta | Generarlo exigiría una herramienta adicional que necesita Java. Con una única operación no compensa. Se revisará cuando crezca la API. |
| D-8 | No se usa Spring Boot Actuator | Propuesta | La operación de estado propia ya cubre la spec (RNF-6). |
| D-9 | En esta funcionalidad, `infra/` solo contiene el Compose, `.env.example` y el `.env` local | Propuesta | La configuración de RabbitMQ y Kafka se añadirá en sus propias especificaciones. |

## 3. Backend

### 3.1 Proyecto
- Maven con Maven Wrapper (`./mvnw`), Java 21 y Spring Boot 4.1.2 (la última versión estable al implementar), fijada en el `pom.xml`.
- `groupId` `com.messaginglab`, `artifactId` `messaging-lab-backend`, versión inicial `0.1.0`.
- **Dependencias** (justificación según RNF-6; nombres de Spring Boot 4, que modularizó los starters):
  - `spring-boot-starter-webmvc`: API REST. Equivale al antiguo `spring-boot-starter-web`.
  - `spring-boot-starter-validation`: necesaria para las anotaciones de validación del código generado.
  - `spring-boot-starter-webmvc-test` (solo para pruebas). Equivale al antiguo `spring-boot-starter-test` y añade el soporte de pruebas de la capa web (JUnit, Mockito, MockMvc). No se incluye `spring-boot-starter-validation-test`, porque esta funcionalidad no lo necesita.
  - `openapi-generator-maven-plugin` 7.26.0 (solo en tiempo de compilación; versión en la propiedad `openapi-generator.version`). Usa el generador `spring` con `inputSpec` `${project.basedir}/api/api.yml`, `apiPackage` `com.messaginglab.api` y `modelPackage` `com.messaginglab.api.model`. Opciones (`configOptions`):

    | Opción | Valor | Motivo |
    |--------|-------|--------|
    | `interfaceOnly` | `true` | Solo genera la interfaz y los modelos; el controlador propio la implementa. |
    | `useSpringBoot4` | `true` | Código compatible con Spring Boot 4. |
    | `useJackson3` | `true` | Alinea el código generado con Jackson 3 (`tools.jackson`), el que usa Spring Boot 4. |
    | `useJspecify` | `true` | Anotaciones de nulabilidad de JSpecify en lugar de `org.springframework.lang.Nullable`, obsoleta en Spring 7. JSpecify ya llega de forma transitiva con Spring. |
    | `useTags` | `true` | Agrupa las operaciones por etiqueta, de modo que la interfaz se llama `StatusApi`. |
    | `documentationProvider` | `none` | Evita la dependencia de springdoc/Swagger UI. |
    | `annotationLibrary` | `none` | Evita la dependencia de las anotaciones de Swagger. |
    | `openApiNullable` | `false` | Evita la dependencia de `jackson-databind-nullable`. |
    | `generateJsonIncludeAnnotations` | `false` | Mantiene el comportamiento por defecto y silencia el aviso del generador. |
    | `generateJsonSetterNullsAnnotations` | `false` | Mantiene el comportamiento por defecto y silencia el aviso del generador. |
- `spring-boot-maven-plugin` con el objetivo `build-info`. Genera los metadatos de compilación a partir del `pom.xml` y es la fuente única de la versión (CA-1.2). Ese objetivo se ejecuta tanto en `spring-boot:run` como en el empaquetado, por lo que la versión es la misma en local y en contenedores (CA-1.4).

### 3.2 Contrato — `backend/api/api.yml`
- OpenAPI 3.0.3, que es la versión con soporte completo en el generador.
- `GET /api/status`, con `operationId: getStatus` y la etiqueta `Status`.
- Respuesta 200 con el esquema `StatusResponse`. Sus tres campos son obligatorios:
  - `status`: enumerado `AVAILABLE`.
  - `name`: string.
  - `version`: string.
- Código generado en `target/generated-sources`, con la interfaz en `com.messaginglab.api` y los modelos en `com.messaginglab.api.model`. No se versiona.

### 3.3 Capas
```
backend/src/main/java/com/messaginglab/
├── MessagingLabApplication.java
├── controller/StatusController.java     # implementa StatusApi (generada) y traduce del dominio al DTO
├── service/StatusService.java           # construye el estado a partir de BuildProperties
└── model/ApplicationStatus.java         # record de dominio (status, name, version), independiente del DTO
```
- Configuración en `application.properties`:
  - `spring.application.name=messaging-lab`
  - `server.port=${BACKEND_PORT:8080}`
  - `spring.config.import=optional:file:../infra/.env[.properties]`, que lee `infra/.env` al arrancar desde `backend/` y no falla si no existe.
- El dominio no depende de las clases generadas. Si el contrato cambia, solo se toca el controlador.

### 3.4 Dockerfile — `backend/Dockerfile`
- Multietapa: compila con una imagen `eclipse-temurin:21-jdk` usando `./mvnw package -DskipTests` y ejecuta con `eclipse-temurin:21-jre` como usuario sin privilegios. Escucha siempre en el puerto 8080 dentro del contenedor.
- `backend/.dockerignore` excluye `target/`.

## 4. Frontend

### 4.1 Proyecto
- Angular 21 (CLI), standalone y zoneless, con Vitest como ejecutor de pruebas (el predeterminado de Angular 21, que se lanza con `npm test`).
- No se añaden dependencias de interfaz (ni Material ni similares); los estilos son CSS propio.

### 4.2 Organización
```
frontend/src/app/
├── app.config.ts / app.routes.ts / app.ts
├── core/
│   └── status/
│       ├── backend-status.ts            # tipo BackendStatus y validación isBackendStatus()
│       └── status-api.service.ts        # GET /api/status con timeout(20000) y clasificación de errores
├── layout/
│   ├── shell/                           # cabecera + menú + <router-outlet> (CA-4.1)
│   └── nav-sections.ts                  # secciones del menú: etiqueta, descripción, habilitada
└── features/
    └── status/status-page/              # vista de estado: carga / éxito / error + reintentar
```

### 4.3 Comportamiento
- **Rutas:** `''` redirige a `status`; `status` muestra la vista de estado; `**` redirige a `status` (CA-4.4).
- **Menú (CA-4.2, CA-4.3, CA-4.5):** «Estado» está activo. «Laboratorio RabbitMQ», «Laboratorio Kafka» y «Visualizador de flujo» aparecen con su descripción, como elementos con `aria-disabled="true"` que no navegan. Al activarlos se muestra «Disponible próximamente».
- **Estado de la vista:** una única signal con tres casos: `loading`, `success(data)` y `error(message)`.
- **Clasificación de errores en `StatusApiService`** (CA-3.1, CA-3.2):

| Situación | Mensaje |
|-----------|---------|
| Más de 20 s sin respuesta, error de red (HTTP 0) o el proxy no alcanza el backend (502, 503, 504) | «El backend no está disponible.» |
| Otro error HTTP o respuesta que no supera `isBackendStatus()` (por ejemplo, sin `version`) | «No se ha podido obtener el estado del backend.» |

- **Reintentar (CA-3.3 a CA-3.6):** el botón vuelve a lanzar la consulta sin recargar la página y está deshabilitado mientras la vista está en `loading`.
- **Accesibilidad (RNF-8):** la zona de estado usa `aria-live="polite"` y el error `role="alert"`. El botón es un `<button>` nativo, así que se puede activar con el teclado.
- El valor `AVAILABLE` se muestra en la interfaz como «Disponible».

### 4.4 Desarrollo local y puertos
- `npm start` ejecuta `frontend/scripts/serve.mjs`, un script de unas pocas líneas sin dependencias. Hace tres cosas:
  1. Carga `../infra/.env` si existe, con `process.loadEnvFile`, que es nativo de Node.
  2. Arranca `ng serve --port ${FRONTEND_PORT:-4200} --proxy-config proxy.conf.mjs`.
  3. Hace que `proxy.conf.mjs` reenvíe `/api` a `http://localhost:${BACKEND_PORT:-8080}`.
- Así, cambiar `BACKEND_PORT` en `infra/.env` ajusta a la vez el backend y el proxy, y CL-9 no se produce en local.

### 4.5 Dockerfile — `frontend/Dockerfile`
- Multietapa: compila con `node` LTS (Alpine) usando `npm ci` y `ng build`, y sirve con `nginx` (Alpine).
- `frontend/nginx.conf`:
  - `location /api/` hace `proxy_pass` a `http://backend:8080`.
  - `location /` hace `try_files $uri /index.html` para que las rutas de Angular funcionen al recargar.
- `frontend/.dockerignore` excluye `node_modules/`, `dist/` y `.angular/`.

## 5. Arranque completo y configuración

- **`infra/docker-compose.yml`** con dos servicios. Las rutas de `build` son relativas a `infra/`:
  - `backend`: `build: ../backend`, puertos `"${BACKEND_PORT:-8080}:8080"`. Exponerlo permite consultar la API directamente.
  - `frontend`: `build: ../frontend`, puertos `"${FRONTEND_PORT:-4200}:80"` y `depends_on: backend`, de modo que el nombre `backend` exista al arrancar nginx. No se espera a que el backend esté listo: si la interfaz carga antes, se aplica RF-3 (CA-6.4, CL-2).
- **Comandos** (desde la raíz, como en AGENTS.md):
  - Arrancar: `docker compose -f infra/docker-compose.yml up -d --build`. Con `--build` se reconstruyen las imágenes si ha cambiado el código.
  - Parar: `docker compose -f infra/docker-compose.yml down` (CA-6.3).
- **`infra/.env.example`** (versionado) con `BACKEND_PORT=8080` y `FRONTEND_PORT=4200` comentados. **`infra/.env`** queda ignorado.
- **`.gitignore`** en la raíz: `infra/.env`, `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, `frontend/.angular/` y archivos del IDE y del sistema.
- Nota: en contenedores, `BACKEND_PORT` solo cambia el puerto publicado en la máquina. La comunicación interna entre nginx y el backend usa siempre `backend:8080`, así que cambiar puertos no rompe nada (CA-6.5).

## 6. Pruebas (RF-7)

**Backend** (`./mvnw test`):
- `StatusControllerTest` (MockMvc, capa web con `StatusService` simulado): `GET /api/status` responde 200 con `status`, `name` y `version` (CA-7.1).
- `StatusServiceTest` (unitaria): construye el estado a partir de `BuildProperties`.
- `MessagingLabApplicationTests` (contexto completo): la aplicación arranca y la versión devuelta coincide con la de `BuildProperties` (CA-1.2, CA-1.4).
- La generación desde `api.yml` valida el contrato en cada compilación.

**Frontend** (`npm test`, Vitest + `HttpTestingController`; no necesita el backend en marcha, CA-7.4):
- `status-api.service.spec`: éxito, respuesta incompleta, 500, 502 y *timeout* (con temporizadores simulados).
- `status-page.spec`: muestra la carga, muestra los datos (CA-7.2), muestra el error, reintenta con éxito y deshabilita el botón durante la carga (CA-7.3).
- `shell.spec`: cabecera, secciones deshabilitadas con su descripción y aviso «Disponible próximamente» sin navegar.
- `app.routes.spec`: una ruta desconocida redirige a `status`.

## 7. Documentación

- **`README.md`** (en español):
  - requisitos previos (JDK 21, Node LTS compatible con Angular 21, Docker con Compose v2);
  - configuración de puertos copiando `infra/.env.example` a `infra/.env`;
  - arranque local por componentes;
  - arranque completo y parada;
  - ejecución de pruebas;
  - cómo comprobar que la aplicación funciona;
  - puertos por defecto.

  Cubre CA-8.1 a CA-8.5 y RNF-2.
- **`AGENTS.md`** no necesita cambios estructurales. Se añade `--build` al comando de arranque de la infraestructura para que las imágenes de backend y frontend se reconstruyan al cambiar el código. Aprobado el 2026-10-30.

## 8. Trazabilidad

| Requisito | Dónde se resuelve |
|-----------|-------------------|
| RF-1 | §3.2, §3.3, `build-info` |
| RF-2, RF-3 | §4.3 |
| RF-4 | §4.2, §4.3 |
| RF-5 | §3.3, §4.4 |
| RF-6 | §3.4, §4.5, §5 |
| RF-7 | §6 |
| RF-8 | §7 |
| RNF-4 | `timeout(20000)` en `StatusApiService` |
| RNF-5 | `infra/.env` ignorado; `infra/.env.example` sin secretos |
| RNF-9 | Verificación manual en la última versión estable de Chrome |

## 9. Verificación final

1. `cd backend && ./mvnw test` y `cd frontend && npm test` pasan.
2. Local: `./mvnw spring-boot:run` + `npm start` → en `http://localhost:4200` se ve «Disponible», `messaging-lab-backend` y `0.1.0`.
3. Se para el backend, se pulsa «Reintentar» y aparece el error; se arranca el backend, se pulsa «Reintentar» y aparece el estado.
4. Se cambian los puertos en `infra/.env` y se repite el paso 2 con los nuevos puertos.
5. Desde la raíz, `docker compose -f infra/docker-compose.yml up -d --build` → en `http://localhost:4200` se ve la misma versión; `docker compose -f infra/docker-compose.yml down` lo detiene todo.
6. Una ruta inexistente redirige a la vista de estado, y las secciones deshabilitadas no navegan.
7. Todo lo anterior se comprueba en Chrome estable.

## 10. Riesgos

- **Versiones:** resuelto en T04. OpenAPI Generator 7.26.0 es compatible con Spring Boot 4.1.2 mediante `useSpringBoot4` y `useJackson3` (ver §3.1). Al actualizar cualquiera de los dos, hay que comprobar de nuevo la compatibilidad.
- **Node:** `process.loadEnvFile` necesita Node ≥ 20.12. Angular 21 ya exige una versión superior, así que no añade restricciones.

# messaging-lab

Laboratorio visual para aprender, experimentar y comparar sistemas de mensajería con RabbitMQ y Apache Kafka.

Estado actual: **base del proyecto** ([especificación 01](specs/01-project-foundation/spec.md)). La interfaz web se comunica con el backend y muestra su estado, nombre y versión. Los laboratorios de RabbitMQ y Kafka y el visualizador de flujo aparecen en el menú como «Disponible próximamente».

## Contenido del repositorio

| Carpeta | Contenido |
|---------|-----------|
| `backend/` | Aplicación Spring Boot (Java 21), pruebas y contrato OpenAPI (`backend/api/api.yml`) |
| `frontend/` | Aplicación Angular 21 y sus pruebas |
| `infra/` | Docker Compose y plantilla de configuración local |
| `specs/` | Especificaciones, planes y tareas por funcionalidad |
| `docs/` | Constitución y documentación general del proyecto |

## Requisitos previos

| Herramienta | Versión | Para qué |
|-------------|---------|----------|
| JDK | 21 | Backend en local y sus pruebas |
| Node.js | 20.19, 22.12 o 24 y superiores (probado con 24) | Frontend en local y sus pruebas |
| Docker con Compose v2 | Docker 29 / Compose 5 (probado) | Arranque completo con un único comando |
| Google Chrome | Última versión estable | Navegador en el que se verifica la interfaz |

No hace falta instalar Maven: el proyecto incluye Maven Wrapper (`./mvnw`).

Para el arranque local por componentes necesitas JDK y Node.js; para el arranque completo solo necesitas Docker.

## Puertos y configuración

| Componente | Puerto por defecto |
|------------|--------------------|
| Frontend (interfaz web) | `4200` |
| Backend (API) | `8080` |

No hace falta configurar nada para usar los puertos por defecto. Para cambiarlos **sin modificar ningún archivo versionado**:

1. Copia la plantilla: `cp infra/.env.example infra/.env`
2. Edita `infra/.env` y descomenta las variables que quieras cambiar:

   ```
   BACKEND_PORT=8081
   FRONTEND_PORT=4300
   ```

3. Reinicia los componentes.

`infra/.env` está ignorado por git. Es un único archivo que leen el backend, el frontend y Docker Compose, así que el cambio se aplica a todos y la interfaz sigue encontrando el backend.

Ten en cuenta:

- **El backend y el frontend deben leer el mismo puerto.** Si cambias el puerto del backend de otra forma (por ejemplo, con `--server.port` al arrancarlo), la interfaz seguirá buscándolo en el puerto de `infra/.env` y mostrará «El backend no está disponible.». Cambia siempre `BACKEND_PORT` en `infra/.env`.
- **`npm start` es el que carga `infra/.env`.** Si ejecutas `ng serve` directamente, se usan los puertos por defecto.
- **Una variable definida en tu terminal tiene prioridad** sobre la de `infra/.env`.
- **En el arranque completo**, `BACKEND_PORT` y `FRONTEND_PORT` solo cambian los puertos publicados en tu máquina. La comunicación interna entre contenedores no cambia.

## Arranque

Hay dos formas de arrancar la aplicación. Ambas muestran lo mismo.

### Opción 1: arranque completo con un único comando (Docker)

Desde la raíz del repositorio:

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

La primera vez tarda unos minutos porque construye las imágenes. Después abre <http://localhost:4200>.

Para ver los registros: `docker compose -f infra/docker-compose.yml logs -f`

Para detenerlo todo:

```bash
docker compose -f infra/docker-compose.yml down
```

### Opción 2: arranque local por componentes

Necesitas dos terminales, desde la raíz del repositorio.

**Terminal 1 — backend:**

```bash
cd backend && ./mvnw spring-boot:run
```

**Terminal 2 — frontend** (la primera vez, instala las dependencias):

```bash
cd frontend
npm ci
npm start
```

Después abre <http://localhost:4200>. Para detener cada componente, pulsa `Ctrl+C` en su terminal.

## Comprobar que funciona

1. Abre <http://localhost:4200>. Debes ver la cabecera `messaging-lab`, el menú y, en la vista **Estado del backend**: **Disponible**, la aplicación `messaging-lab-backend` y la versión `0.1.0`.
2. Comprueba la API directamente:

   ```bash
   curl http://localhost:8080/api/status
   ```

   Respuesta esperada: `{"status":"AVAILABLE","name":"messaging-lab-backend","version":"0.1.0"}`

3. Comprueba el comportamiento ante un fallo: detén el backend y recarga la página. Debe aparecer **«El backend no está disponible.»** con un botón **Reintentar**. Vuelve a arrancar el backend y pulsa el botón: la interfaz mostrará el estado sin recargar la página.

El contrato de la API está en [`backend/api/api.yml`](backend/api/api.yml).

## Pruebas

Las pruebas de cada componente se ejecutan por separado y no necesitan que el otro esté en marcha.

**Backend:**

```bash
cd backend && ./mvnw test
```

**Frontend:**

```bash
cd frontend && npm test
```

En una terminal interactiva, `npm test` se queda vigilando los cambios. Para una sola ejecución que termine:

```bash
cd frontend && npm test -- --watch=false
```

## Problemas frecuentes

- **El arranque falla porque un puerto está ocupado.** Elige otros puertos en `infra/.env` (ver [Puertos y configuración](#puertos-y-configuración)).
- **La interfaz muestra «El backend no está disponible.»** El backend no ha terminado de arrancar o está detenido, o el frontend y el backend usan puertos distintos. Espera unos segundos y pulsa **Reintentar**. Si persiste, revisa que `BACKEND_PORT` sea el correcto.
- **Cambio el código y en Docker no se nota.** Usa siempre `up -d --build`; sin `--build` Docker reutiliza las imágenes anteriores.

## Documentación del proyecto

- [`docs/constitution.md`](docs/constitution.md): principios del proyecto.
- [`specs/`](specs/): especificaciones, planes y tareas de cada funcionalidad.
- [`AGENTS.md`](AGENTS.md): instrucciones para los agentes de IA.

# messaging-lab

Laboratorio visual para aprender, experimentar y comparar sistemas de mensajería con RabbitMQ y Apache Kafka.

Estado actual:

- **Base del proyecto** ([especificación 01](specs/01-project-foundation/spec.md)): la interfaz web se comunica con el backend y muestra su estado, nombre y versión.
- **Laboratorio RabbitMQ** ([especificación 02](specs/02-rabbitmq-integration/spec.md)): crea exchanges, colas y bindings, publica mensajes, inspecciónalos y consúmelos decidiendo qué hacer con cada uno (confirmar, rechazar y reencolar, o rechazar a una dead-letter queue).
- El laboratorio de Kafka y el visualizador de flujo aparecen en el menú como «Disponible próximamente».

## Contenido del repositorio

| Carpeta | Contenido |
|---------|-----------|
| `backend/` | Aplicación Spring Boot (Java 21), pruebas y contrato OpenAPI (`backend/api/api.yml`) |
| `frontend/` | Aplicación Angular 21 y sus pruebas |
| `infra/` | Docker Compose (RabbitMQ, backend y frontend) y plantilla de configuración local |
| `specs/` | Especificaciones, planes y tareas por funcionalidad |
| `docs/` | Constitución y documentación general del proyecto |

## Requisitos previos

| Herramienta | Versión | Para qué |
|-------------|---------|----------|
| JDK | 21 | Backend en local y sus pruebas |
| Node.js | 20.19, 22.12 o 24 y superiores (probado con 24) | Frontend en local y sus pruebas |
| Docker con Compose v2 | Docker 29 / Compose 5 (probado) | RabbitMQ, arranque completo con un único comando y pruebas de integración |
| Google Chrome | Última versión estable | Navegador en el que se verifica la interfaz |

No hace falta instalar Maven: el proyecto incluye Maven Wrapper (`./mvnw`).

Para el arranque local por componentes necesitas JDK, Node.js y Docker (para ejecutar RabbitMQ); para el arranque completo solo necesitas Docker.

## Puertos y configuración

| Componente | Puerto por defecto |
|------------|--------------------|
| Frontend (interfaz web) | `4200` |
| Backend (API) | `8080` |
| RabbitMQ (AMQP) | `5672` |
| Consola de administración de RabbitMQ | `15672` |

No hace falta configurar nada para usar los valores por defecto. Para cambiarlos **sin modificar ningún archivo versionado**:

1. Copia la plantilla: `cp infra/.env.example infra/.env`
2. Edita `infra/.env` y descomenta las variables que quieras cambiar:

   ```
   BACKEND_PORT=8081
   FRONTEND_PORT=4300
   RABBITMQ_PORT=5673
   RABBITMQ_MANAGEMENT_PORT=15673
   RABBITMQ_USER=guest
   RABBITMQ_PASSWORD=guest
   HEARTBEAT_INTERVAL=15s
   ```

3. Reinicia los componentes.

`infra/.env` está ignorado por git. Es un único archivo que leen el backend, el frontend y Docker Compose, así que el cambio se aplica a todos y la interfaz sigue encontrando el backend.

Ten en cuenta:

- **El backend y el frontend deben leer el mismo puerto.** Si cambias el puerto del backend de otra forma (por ejemplo, con `--server.port` al arrancarlo), la interfaz seguirá buscándolo en el puerto de `infra/.env` y mostrará «El backend no está disponible.». Cambia siempre `BACKEND_PORT` en `infra/.env`.
- **`npm start` es el que carga `infra/.env`.** Si ejecutas `ng serve` directamente, se usan los puertos por defecto.
- **Una variable definida en tu terminal tiene prioridad** sobre la de `infra/.env`.
- **En el arranque completo**, `BACKEND_PORT`, `FRONTEND_PORT`, `RABBITMQ_PORT` y `RABBITMQ_MANAGEMENT_PORT` solo cambian los puertos publicados en tu máquina. La comunicación interna entre contenedores no cambia.
- **Los puertos de RabbitMQ solo son accesibles desde tu máquina** (`127.0.0.1`), no desde otros equipos de la red.
- **`HEARTBEAT_INTERVAL`** (por defecto `15s`) es cada cuánto envía el backend un latido por el flujo de eventos de los consumidores. Ver [Cerrar la pestaña con consumidores activos](#cerrar-la-pestaña-con-consumidores-activos).

### Credenciales de RabbitMQ

Por defecto se usan `guest` / `guest`, las credenciales públicas de RabbitMQ. **No son un secreto y están en el repositorio a propósito**: solo sirven para el desarrollo local, porque RabbitMQ únicamente es accesible desde tu máquina. No las uses en un entorno compartido ni expuesto a una red. Si quieres otras, define `RABBITMQ_USER` y `RABBITMQ_PASSWORD` en `infra/.env` (o en tu terminal) y reinicia; si ya tenías datos creados con otras credenciales, elimínalos antes (ver [Datos de RabbitMQ](#datos-de-rabbitmq)).

La consola de administración de RabbitMQ está en <http://localhost:15672> y entra con esas mismas credenciales. Sirve para contrastar lo que ves en el laboratorio.

## Arranque

Hay dos formas de arrancar la aplicación. Ambas muestran lo mismo.

### Opción 1: arranque completo con un único comando (Docker)

Desde la raíz del repositorio:

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

Levanta RabbitMQ, el backend y el frontend. La primera vez tarda unos minutos porque construye las imágenes. Después abre <http://localhost:4200>.

Para ver los registros: `docker compose -f infra/docker-compose.yml logs -f`

Para detenerlo todo (conserva los datos de RabbitMQ):

```bash
docker compose -f infra/docker-compose.yml down
```

### Opción 2: arranque local por componentes

Es la forma cómoda para desarrollar: solo RabbitMQ va en Docker. Necesitas tres terminales, desde la raíz del repositorio.

**Terminal 1 — solo RabbitMQ:**

```bash
docker compose -f infra/docker-compose.yml up -d rabbitmq
```

El backend arranca aunque RabbitMQ aún no esté listo y se conecta cuando lo está.

**Terminal 2 — backend:**

```bash
cd backend && ./mvnw spring-boot:run
```

**Terminal 3 — frontend** (la primera vez, instala las dependencias):

```bash
cd frontend
npm ci
npm start
```

Después abre <http://localhost:4200>. Para detener el backend y el frontend, pulsa `Ctrl+C` en su terminal; RabbitMQ se detiene con `docker compose -f infra/docker-compose.yml down`.

### Datos de RabbitMQ

Los exchanges, las colas, los bindings y los mensajes se guardan en un volumen de Docker y **sobreviven** a detener y volver a levantar RabbitMQ.

| Quiero | Comando |
|--------|---------|
| Detener conservando los datos | `docker compose -f infra/docker-compose.yml down` |
| Empezar de cero (borra exchanges, colas, bindings y mensajes) | `docker compose -f infra/docker-compose.yml down -v` |

## Comprobar que funciona

1. Abre <http://localhost:4200>. Debes ver la cabecera `messaging-lab`, el menú y, en la vista **Estado del backend**: **Disponible**, la aplicación `messaging-lab-backend` y la versión `0.1.0`.
2. Comprueba la API directamente:

   ```bash
   curl http://localhost:8080/api/status
   ```

   Respuesta esperada: `{"status":"AVAILABLE","name":"messaging-lab-backend","version":"0.1.0"}`

3. Comprueba el comportamiento ante un fallo: detén el backend y recarga la página. Debe aparecer **«El backend no está disponible.»** con un botón **Reintentar**. Vuelve a arrancar el backend y pulsa el botón: la interfaz mostrará el estado sin recargar la página.

### Escenario guiado del Laboratorio RabbitMQ

Con todo en marcha, entra en **Laboratorio RabbitMQ** en el menú. Debe poner **Conectado**. Sigue estos pasos:

1. **Exchanges:** crea un exchange de tipo `topic` llamado `eventos`.
2. **Colas:** crea una cola `fallidos`. Después crea una cola `pedidos` eligiendo `fallidos` como dead-letter queue.
3. **Bindings:** crea un binding de `eventos` a `pedidos` con el patrón `pedido.*`.
4. **Publicación:** publica en `eventos` con la routing key `pedido.creado` y el cuerpo que quieras. Debe decir que se ha enrutado. Publica otro con `otra.cosa`: debe decir que no se ha podido enrutar y se ha descartado.
5. **Colas:** pulsa **Actualizar** y comprueba que `pedidos` tiene 1 mensaje listo. Pulsa **Inspeccionar** para ver su contenido sin consumirlo.
6. **Consumidores:** inicia un consumidor en modo **manual** sobre `pedidos`. Recibirá el mensaje. Pulsa **Rechazar sin reencolar**: el mensaje pasa a `fallidos`.
7. **Colas:** inspecciona `fallidos`. Verás el mensaje con su cola de origen (`pedidos`) y el motivo (`rejected`).

Para ver el reparto entre consumidores, inicia dos sobre la misma cola y publica varios mensajes. Al salir del laboratorio o cerrar la pestaña, los consumidores se detienen y los mensajes que tuvieran pendientes de confirmar vuelven a la cola.

**Limitaciones que conviene conocer:**

- La inspección no retira los mensajes, pero RabbitMQ no ofrece una lectura sin efecto: puede dejarlos marcados como **reentregados**. La interfaz lo avisa.
- Al cerrar o recargar la pestaña, el servidor tarda hasta unos 30 segundos en darse cuenta y devolver a la cola los mensajes pendientes (ver [más abajo](#cerrar-la-pestaña-con-consumidores-activos), donde se explica cómo reducirlo). Cambiar de área dentro del laboratorio no detiene los consumidores.
- **Ciclos de dead-letter queues:** si varias colas se configuran como dead-letter queue unas de otras formando un ciclo, RabbitMQ **descarta los mensajes en silencio**. El laboratorio solo impide que una cola sea su propia dead-letter queue; los ciclos más largos no se detectan.

El contrato de la API está en [`backend/api/api.yml`](backend/api/api.yml).

#### Cerrar la pestaña con consumidores activos

Los consumidores viven mientras la pestaña mantiene abierto su flujo de eventos. Cuando la pestaña se cierra o se recarga, el navegador no avisa al servidor: este solo se da cuenta cuando falla el siguiente **latido** que envía por ese flujo (uno cada 15 segundos por defecto), normalmente al segundo. Hasta entonces, un mensaje que el consumidor tenía pendiente de confirmar sigue sin verse en la cola; después vuelve a ella marcado como reentregado. Eso son **hasta unos 30 segundos** con el valor por defecto.

Si prefieres que se note menos, reduce el intervalo con `HEARTBEAT_INTERVAL`:

- **Arranque completo (Docker) o backend en local:** descomenta `HEARTBEAT_INTERVAL` en `infra/.env` (por ejemplo, `HEARTBEAT_INTERVAL=5s`) y reinicia el backend. En Docker, `docker compose -f infra/docker-compose.yml up -d` vuelve a crear el contenedor con el valor nuevo.
- **Una sola vez, desde la terminal:** `HEARTBEAT_INTERVAL=5s ./mvnw spring-boot:run`.

Un intervalo menor reduce esa espera, a costa de enviar más latidos. Con `5s`, la espera pasa a ser de unos 10 segundos como máximo.

### Generador de tráfico

La pestaña **Generador** del Laboratorio RabbitMQ publica mensajes sin parar, como lo haría un robot o un sensor, para ver cómo se comportan las colas con carga continua. Los emisores corren en el **backend**: siguen funcionando aunque cambies de pestaña o cierres el navegador, hasta que los pares o reinicies el backend (al reiniciar no queda nada en marcha).

1. En **Nuevo emisor**, elige una plantilla del catálogo (robot UR, temperatura, sonómetro, vibración, tag de PLC, alarma) o escribe la tuya, y pulsa **Vista previa** para ver un mensaje de ejemplo.
2. Elige el destino (un exchange con su routing key, o directamente una cola) y la frecuencia: **fija** (cada N ms) o **aleatoria dentro de un rango**.
3. Pulsa **Crear emisor** y luego **Iniciar**. Puedes pausarlo, pararlo o eliminarlo. **Parar todo** detiene emisores y consumidores simulados.
4. Para que una cola se vacíe, crea un **consumidor simulado** (tiempo de proceso, porcentaje de fallos y si el mensaje fallido se devuelve a la cola o se rechaza hacia la dead-letter queue). Un emisor más rápido que su consumidor hace crecer la cola, y se ve en **Colas**, que se actualiza sola cada 2 s.

**Plantillas.** Son texto con funciones entre `{{ }}`: `numero`, `deriva`, `senoidal`, `contador`, `ahora`, `elegir`, `booleano` y `ejes`. No se ejecuta código; una plantilla inválida se rechaza indicando línea y columna.

**Límites de seguridad.** La suma de los emisores en marcha no puede superar un límite global (100 mensajes por segundo por defecto, editable en la pantalla hasta un máximo absoluto de 500). Hay un máximo de 20 emisores y 20 consumidores simulados. Cuando una cola supera los 10 000 mensajes listos, la pantalla avisa.

**Dos tipos de consumidor.** Los *simulados* (Generador) consumen solos en el servidor. Los *de sesión* (Consumidores) los controlas tú mensaje a mensaje y se detienen al cerrar la pestaña.

Contrato: operaciones `/api/rabbitmq/generator/*` en [`backend/api/api.yml`](backend/api/api.yml). Especificación: [`specs/04-rabbitmq-traffic-generator/`](specs/04-rabbitmq-traffic-generator/).

## Pruebas

Las pruebas de cada componente se ejecutan por separado y no necesitan que el otro esté en marcha.

**Backend** (no necesita Docker ni RabbitMQ):

```bash
cd backend && ./mvnw test
```

**Backend, con pruebas de integración contra un RabbitMQ real:**

```bash
cd backend && ./mvnw verify
```

Necesitan **Docker en marcha**: arrancan un RabbitMQ desechable con Testcontainers (la primera vez descargan la imagen `rabbitmq:4.4-management`) y no usan el que tengas levantado ni tus datos. Tardan unos minutos.

**Frontend** (no necesita backend ni RabbitMQ):

```bash
cd frontend && npm test
```

En una terminal interactiva, `npm test` se queda vigilando los cambios. Para una sola ejecución que termine:

```bash
cd frontend && npm test -- --watch=false
```

## Problemas frecuentes

- **El arranque falla porque un puerto está ocupado.** Elige otros puertos en `infra/.env` (ver [Puertos y configuración](#puertos-y-configuración)). Sirve para el frontend, el backend, RabbitMQ y su consola. Para saber quién usa un puerto: `lsof -iTCP:5672 -sTCP:LISTEN`.
- **RabbitMQ no arranca.** Mira sus registros: `docker compose -f infra/docker-compose.yml logs rabbitmq`. Las causas habituales son un puerto ocupado, Docker sin memoria suficiente o datos antiguos incompatibles: en ese último caso puedes empezar de cero con `down -v` (borra los datos).
- **El laboratorio muestra «RabbitMQ no está disponible.»** RabbitMQ está detenido o aún arrancando. Comprueba que está en marcha (`docker compose -f infra/docker-compose.yml ps`) y pulsa **Reintentar**: no hace falta recargar la página ni reiniciar el backend.
- **El laboratorio dice que RabbitMQ ha rechazado las credenciales.** Las credenciales que usa el backend no coinciden con las de los datos de RabbitMQ. Usa las mismas en `infra/.env` o elimina los datos con `down -v`.
- **La interfaz muestra «El backend no está disponible.»** El backend no ha terminado de arrancar o está detenido, o el frontend y el backend usan puertos distintos. Espera unos segundos y pulsa **Reintentar**. Si persiste, revisa que `BACKEND_PORT` sea el correcto.
- **Los consumidores no reciben mensajes en el arranque completo.** Reconstruye con `up -d --build`: la configuración de nginx que deja pasar el flujo de eventos se aplica al construir la imagen del frontend.
- **Cambio el código y en Docker no se nota.** Usa siempre `up -d --build`; sin `--build` Docker reutiliza las imágenes anteriores.
- **`./mvnw verify` falla enseguida.** Comprueba que Docker está en marcha (`docker info`).

## Documentación del proyecto

- [`docs/constitution.md`](docs/constitution.md): principios del proyecto.
- [`specs/`](specs/): especificaciones, planes y tareas de cada funcionalidad.
- [`AGENTS.md`](AGENTS.md): instrucciones para los agentes de IA.

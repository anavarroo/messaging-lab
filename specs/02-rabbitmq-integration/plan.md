# Plan 02 — Integración con RabbitMQ

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Especificación:** [spec.md](spec.md) (aprobada)

## 1. Resumen

El backend gana una capa de acceso a RabbitMQ con dos canales, cada uno para lo que sabe hacer:

- **API HTTP de gestión de RabbitMQ**: crear, listar y borrar exchanges, colas y bindings, leer contadores e inspeccionar mensajes. El protocolo AMQP no permite listar nada, así que esta vía es obligatoria.
- **Protocolo AMQP**: publicar (con confirmación y detección de mensajes no enrutables) y consumir (con confirmaciones manuales).

Los mensajes de los consumidores llegan al navegador por **un único flujo de eventos (SSE) por pestaña**. Ese flujo da vida a los consumidores: al cerrarse la pestaña, se detienen (CA-9.13).

```
Navegador ──/api/rabbitmq/*──> Frontend (proxy / nginx) ──> Backend
   ▲                                                          │
   └──── flujo SSE de eventos (1 por pestaña) ◄── consumidores │
                                                              ▼
                                  Controller → Service → Gateway (puertos)
                                                              │
                                              Adaptador RabbitMQ ──HTTP gestión──┐
                                                              └────AMQP──────────┤
                                                                                 ▼
                                                              RabbitMQ (volumen persistente)
```

## 2. Comprobaciones previas

Antes de decidir he medido el comportamiento real de RabbitMQ 4.4.0 con un contenedor temporal (ya eliminado). Estos resultados condicionan el diseño:

| Pregunta | Resultado | Consecuencia |
|----------|-----------|--------------|
| Crear de nuevo un exchange o cola idénticos, o con otra configuración | `204` si es idéntico y `400` con el motivo si difiere | Cubre CA-4.5 y CA-5.6 sin consulta previa |
| Repetir un binding | `201` siempre, sin avisar de que ya existía | Hay que consultar los bindings antes de crear (CA-6.7) |
| Dead-letter queue con el exchange por defecto como destino y la cola como routing key | Funciona: un rechazo sin reencolar deja el mensaje en la cola indicada | No hace falta crear exchanges auxiliares para las dead-letter queues |
| Origen de un mensaje en la dead-letter queue | Su `exchange` y `routing_key` son los del reenvío; los originales están en la cabecera `x-death` | El adaptador debe leer `x-death` (CA-8.5) |
| Contadores del detalle de una cola | Tardan hasta 5 s, y justo tras crear una cola ni aparecen | Inservible para CA-8.8 |
| Contadores con `disable_stats=true&enable_queue_totals=true` | Se actualizan al instante (0,00–0,01 s) e incluyen listos, pendientes y total | Se usa este listado (CA-8.1, CA-8.8) |
| Inspección (`ack_requeue_true`) dos veces seguidas | **La segunda marca el mensaje como reentregado** | Limitación de RabbitMQ; ver R-1 |
| Cuerpo con HTML y caracteres especiales | Se conserva tal cual | La interfaz debe escapar (RNF-5) |
| Reinicio del contenedor | La topología sobrevive; el nombre del nodo depende del hostname | Hostname fijo y volumen con nombre (D-12) |
| Usuario `guest` desde otro contenedor | Permitido (`loopback_users.guest = false` en la imagen) | Las credenciales por defecto son viables (CA-1.7) |
| Generador de OpenAPI con un endpoint `text/event-stream` | Produce `ResponseEntity<ConsumerEvent>`; con `schemaMappings` produce `ResponseEntity<SseEmitter>` | El contrato sigue siendo la fuente de verdad (D-8) |

## 3. Decisiones

| # | Decisión | Motivo |
|---|----------|--------|
| D-1 | Imagen oficial `rabbitmq:4.4-management` | Incluye la API de gestión y la consola web (CA-1.8). Se fija la versión menor para que sea reproducible |
| D-2 | Gestión por HTTP y publicación/consumo por AMQP | Ver §2: AMQP no lista; HTTP no sirve para consumir con confirmaciones |
| D-3 | Cliente AMQP: `com.rabbitmq:amqp-client` directamente, sin Spring AMQP | Solo se necesita publicar y consumir; Spring AMQP añadiría contenedores de listeners y plantillas que no se usan, y su caché de canales no cierra los canales de verdad, algo necesario para devolver a la cola los mensajes pendientes (CA-9.8). Versión gestionada por Spring Boot (5.30.0) |
| D-4 | Cliente HTTP de gestión: `RestClient` de Spring | Ya está en el proyecto: no añade dependencias |
| D-5 | Dead-letter queue = cola normal usada como destino mediante el exchange por defecto | Una dead-letter queue es una cola más, como pide CA-5.2, sin exchanges ocultos |
| D-6 | Todo lo creado es durable: exchanges, colas (`classic`) y mensajes persistentes | RF-10 |
| D-7 | Arquitectura por capas (D-1 de la 01) más un paquete `gateway` con puertos y un adaptador RabbitMQ | RNF-7 y AGENTS.md: las reglas se prueban con un gateway falso, sin RabbitMQ |
| D-8 | Eventos en tiempo real con SSE; contrato con `schemaMappings` a `SseEmitter` | Es unidireccional y nativo en el navegador (`EventSource`), sin dependencias. WebSocket no aporta nada: las acciones van por POST |
| D-9 | **Un solo flujo SSE por pestaña** que multiplexa todos sus consumidores | HTTP/1.1 limita a 6 conexiones simultáneas por servidor; un flujo por consumidor bloquearía el resto de peticiones con más de cinco. Además hace natural CA-9.13 y CL-23 |
| D-10 | Sin reconexión automática del flujo | Al reconectar el servidor crearía otra sesión y los consumidores anteriores ya no existirían. La interfaz avisa y ofrece reconectar (CA-9.9) |
| D-11 | Operaciones sobre un elemento con nombre: el nombre va en un parámetro de consulta, no en la ruta | Los nombres pueden llevar `/`, `%` o `.`, que Tomcat y las rutas manejan mal |
| D-12 | Compose: servicio `rabbitmq` con **hostname fijo** y **volumen con nombre** | Sin hostname fijo, cada contenedor nuevo tiene otro nombre de nodo y no reutiliza los datos tras `down` y `up` |
| D-13 | Puertos de RabbitMQ publicados solo en `127.0.0.1` | CA-1.6 |
| D-14 | Credenciales por defecto de RabbitMQ (`guest`/`guest`) si no hay configuración externa | CA-1.7. Ver R-2 |
| D-15 | Errores del backend: `ApiError { code, message }` con mensaje en español | RF-11. Se evita el nombre `ProblemDetail`, que chocaría con la clase de Spring |
| D-16 | Pruebas de integración con Testcontainers, ejecutadas con `./mvnw verify`; `./mvnw test` queda sin Docker | CA-12.2 y CA-12.3 |
| D-17 | Frontend: `features/rabbitmq/` con rutas hijas por área y un servicio de sesión de consumidores a nivel del laboratorio | Cambiar de área dentro del laboratorio no detiene los consumidores; salir del laboratorio pide confirmación y los detiene (CA-9.14) |
| D-18 | Confirmaciones con `<dialog>` nativo, sin librerías | Accesible y sin dependencias (RNF-6, RNF-8) |
| D-19 | Reglas de nombre: no vacío, hasta 255 caracteres y 255 bytes UTF-8, sin caracteres de control y sin el prefijo `amq.` | RabbitMQ solo restringe esto en nombres de cliente. Cubre CA-4.4 y CA-5.4 |

## 4. Dependencias nuevas (aprobadas el 2026-10-30)

| Dependencia | Ámbito | Justificación |
|-------------|--------|---------------|
| `com.rabbitmq:amqp-client` | Compilación | Publicar y consumir por AMQP (D-3). Versión gestionada por el BOM de Spring Boot |
| `org.testcontainers:testcontainers-rabbitmq` | Pruebas | Levanta un RabbitMQ real en las pruebas de integración (CA-12.3). Versión gestionada (2.0.5) |
| `org.testcontainers:testcontainers-junit-jupiter` | Pruebas | Ciclo de vida del contenedor en JUnit 5. Versión gestionada (2.0.5) |
| `maven-failsafe-plugin` | Compilación | Separa las pruebas `*IT` de las unitarias (D-16). Versión gestionada (3.5.6) |

No se añade nada al frontend: `EventSource`, `<dialog>` y los formularios reactivos ya están disponibles.

## 5. Backend

### 5.1 Paquetes
```
backend/src/main/java/com/messaginglab/
├── controller/        # un controlador por área, que implementan las interfaces generadas, y ApiExceptionHandler (traduce las excepciones a ApiError)
├── service/           # reglas del laboratorio (validación, DLQ, sesiones de consumidores)
├── model/             # records de dominio, independientes de RabbitMQ y del contrato
├── exception/         # excepciones de dominio (sin dependencias de la web ni del contrato)
└── gateway/           # puertos
    └── rabbitmq/      # adaptador: cliente HTTP de gestión, AMQP, configuración, mapeo de errores
```

### 5.2 Modelo de dominio (`model`)
- `ExchangeType` (`DIRECT`, `FANOUT`, `TOPIC`), `Exchange(name, type, predefined)`.
- `Queue(name, deadLetterQueue, ready, unacknowledged, total)`; `Binding(exchange, queue, routingKey)`.
- `Creation<T>(value, created)` para distinguir «creado» de «ya existía» (CA-4.5, CA-5.6, CA-6.7).
- `PublishRequest(exchange, routingKey, body)`; `PublishResult(routed)`.
- `QueueMessage(body, exchange, routingKey, redelivered, deathInfo)` y `QueueInspection(messages, total, truncated)`.
- Consumidores: `ConsumerInfo(id, name, queue, ackMode)`, `DeliveredMessage(deliveryTag, …)`, `MessageAction` (`ACK`, `REQUEUE`, `REJECT`), `ActionOutcome` (`CONFIRMED`, `REQUEUED`, `DEAD_LETTERED`, `DISCARDED`) y `StopReason` (`USER`, `QUEUE_DELETED`, `CONNECTION_LOST`, `SESSION_CLOSED`).

### 5.3 Puertos (`gateway`)
Tres interfaces, sin ninguna clase de RabbitMQ:
- `BrokerTopology`: estado de la conexión, exchanges, colas (con contadores), bindings, vaciado, inspección.
- `BrokerPublisher`: publicar y devolver si se enrutó.
- `BrokerConsumers`: iniciar un consumidor con un receptor de mensajes y de cierre, y confirmar, rechazar o detenerlo.

### 5.4 Servicios y reglas (`service`)
- `NameValidator`: D-19 y el límite de 255 caracteres de la routing key y el patrón.
- `TopicPatternValidator`: palabras separadas por puntos, con `*` y `#` (CA-6.4).
- `ExchangeService`, `QueueService`, `BindingService`: validan, delegan en el puerto y aplican las reglas propias, entre ellas: una cola no puede ser su propia dead-letter queue (CA-5.5), los exchanges predefinidos no se modifican (CA-4.7), no hay bindings sobre el exchange por defecto (CA-6.9) y el fanout no usa routing key (CA-6.3).
- `PublishService`: cuerpo no vacío y de hasta 64 KB (CA-7.5), codificado en UTF-8.
- `QueueInspectionService`: hasta 50 mensajes y el indicador de truncado (CA-8.3).
- `ConsumerSessionService`: registro en memoria de sesiones y consumidores (no se persiste nada: RNF-10). Controla nombres únicos, un único mensaje pendiente por consumidor manual y que cada mensaje se resuelva una sola vez (CA-9.10).

### 5.5 Adaptador RabbitMQ (`gateway.rabbitmq`)
- **`RabbitMqProperties`**: host, puerto AMQP, puerto de gestión, usuario, contraseña y virtual host `/`. La contraseña no aparece en `toString` ni en los logs.
- **`ManagementApiClient`** (HTTP, tiempo de espera de 10 s):
  - exchanges: `PUT`/`DELETE`/`GET /exchanges/{vhost}`; el estado `201` frente a `204` indica «creado» o «ya existía», y un `400` con `inequivalent arg` se traduce a un conflicto con el motivo en español;
  - colas: `PUT`/`DELETE`, vaciado con `DELETE …/contents`; configuración `durable`, `x-queue-type: classic` y, si se pide, `x-dead-letter-exchange: ""` con `x-dead-letter-routing-key` igual al nombre de la dead-letter queue;
  - contadores: `GET /queues/{vhost}?disable_stats=true&enable_queue_totals=true`;
  - bindings: se consultan los existentes antes de crear; se omiten los implícitos del exchange por defecto; al borrar se calcula la clave del binding (la routing key vacía se codifica como `~`);
  - inspección: `POST …/get` con `ackmode=ack_requeue_true`, `count=50` y `encoding=auto`;
  - mensajes de la dead-letter queue: se toman exchange y routing key originales de `x-death`, y la cola de origen y el motivo de `x-first-death-queue` y `x-first-death-reason`.
- **`AmqpConnectionProvider`**: una conexión compartida, creada de forma perezosa y recreada si está cerrada. **Sin recuperación automática**, para que un corte finalice los consumidores en lugar de reanudarlos en silencio (CA-9.9). El backend arranca aunque RabbitMQ no esté listo (CL-2).
- **`AmqpPublisher`**: un canal por publicación, con confirmaciones, `mandatory=true` y un receptor de devoluciones. Mensaje persistente y texto UTF-8. Resultado `routed=false` si RabbitMQ devuelve el mensaje (código 312). Se espera la confirmación 10 s como máximo.
- **`AmqpConsumers`**: un canal por consumidor. En modo manual, `basicQos(1)` (CA-9.12) y confirmación manual; en automático, confirmación al recibir. Detener el consumidor cierra el canal y RabbitMQ devuelve a la cola los pendientes (CA-9.8). La cancelación de consumidor y el cierre del canal se traducen a `QUEUE_DELETED` y `CONNECTION_LOST`.
- **`BrokerErrorMapper`**: traduce estados HTTP y excepciones de AMQP a las excepciones de dominio. Un `401` se muestra como «RabbitMQ ha rechazado las credenciales configuradas», sin revelarlas.

### 5.6 Contrato (`backend/api/api.yml`)
Todas las rutas cuelgan de `/api/rabbitmq` y los nombres van como parámetro de consulta (D-11).

| Operación | Ruta |
|-----------|------|
| Estado de la conexión | `GET /status` |
| Exchanges | `GET`, `POST /exchanges`; `DELETE /exchanges?name=` |
| Colas | `GET`, `POST /queues`; `DELETE /queues?name=` |
| Vaciar y inspeccionar | `DELETE /queues/messages?queue=`; `GET /queues/messages?queue=` |
| Bindings | `GET /bindings?exchange=&queue=`; `POST /bindings`; `DELETE /bindings?exchange=&queue=&routingKey=` |
| Publicar | `POST /publications` |
| Flujo de la sesión | `GET /session/events` (`text/event-stream`) |
| Consumidores | `POST /sessions/{sessionId}/consumers`; `DELETE /sessions/{sessionId}/consumers/{consumerId}` |
| Resolver un mensaje | `POST /sessions/{sessionId}/consumers/{consumerId}/messages/{deliveryTag}` con `{ action }` |

- Eventos del flujo: `session-started`, `consumer-started`, `message-received` y `consumer-stopped` (con motivo). Un comentario de latido cada 15 s detecta conexiones muertas.
- Respuestas de error: `ApiError { code, message }`, con códigos `VALIDATION`, `NOT_FOUND`, `CONFLICT`, `BROKER_UNAVAILABLE`, `TIMEOUT` y `BROKER_REJECTED`, y estados 400, 404, 409, 503, 504 y 502 respectivamente. Que algo «ya exista» idéntico no es un error (se indica con `created: false`), y si existe con otra configuración es un `CONFLICT`.
- La acción sobre un mensaje devuelve el `ActionOutcome`, de modo que la interfaz puede avisar de que se descartó (CA-9.7).
- Configuración del generador: `schemaMappings` de `ConsumerEventStream` a `SseEmitter`. Los eventos concretos se describen como esquemas independientes.

### 5.7 Configuración
`application.properties` añade `messaginglab.rabbitmq.*` leyendo estas variables, con los valores por defecto indicados:

| Variable | Por defecto | Uso |
|----------|-------------|-----|
| `RABBITMQ_HOST` | `localhost` | Dirección de RabbitMQ |
| `RABBITMQ_PORT` | `5672` | Puerto AMQP |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` | Puerto de la API de gestión y la consola |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Credenciales (D-14) |

## 6. Frontend

### 6.1 Organización
```
frontend/src/app/
├── core/http/
│   ├── api-error.ts                # ApiError y su traducción a mensaje en español
│   └── request-timeout.ts          # tiempo de espera de 20 s compartido (RNF-4)
├── shared/confirm-dialog/          # <dialog> nativo con título, aviso y confirmar/cancelar
└── features/rabbitmq/
    ├── rabbitmq-lab/               # contenedor: estado de la conexión + pestañas + <router-outlet>
    ├── connection/                 # RabbitMqConnection: señal conectado / no disponible / cargando
    ├── exchanges/                  # listado, formulario, borrado
    ├── queues/                     # listado con contadores, formulario, vaciar, borrar, inspección
    ├── bindings/                   # listado filtrable, formulario según el tipo de exchange
    ├── publish/                    # formulario de publicación y resultado
    └── consumers/
        ├── consumer-session.service.ts   # EventSource único + consumidores + mensajes
        └── consumers-page/               # alta, lista de consumidores y mensajes recibidos
```

### 6.2 Comportamiento
- **Rutas:** `/rabbitmq` con hijas `exchanges`, `queues`, `bindings`, `publish` y `consumers`; la vacía redirige a `exchanges`. `ConsumerSessionService` se provee en la ruta `/rabbitmq`, de modo que sigue vivo al cambiar de área y se destruye al salir del laboratorio.
- **Salir del laboratorio (CA-9.14):** un guard `canDeactivate` en la ruta `/rabbitmq` abre el diálogo de confirmación cuando hay consumidores activos. Si el usuario confirma, el servicio los detiene (cierra el flujo y RabbitMQ devuelve los pendientes a la cola); si cancela, la navegación se anula. El guard no actúa al cambiar entre las rutas hijas. Cerrar o recargar la pestaña no pasa por el guard: lo cubre el cierre del flujo en el servidor (CA-9.13).
- **Menú:** «Laboratorio RabbitMQ» pasa a habilitada y navega a `/rabbitmq` (CA-3.1); `nav-sections.ts` conserva las otras dos secciones deshabilitadas (CA-3.2).
- **Conexión (RF-2):** al entrar se consulta `GET /status` con indicador de carga; si falla, mensaje, botón «Reintentar» (deshabilitado mientras dura) y todas las operaciones deshabilitadas.
- **Formularios:** formularios reactivos que replican las validaciones del servidor para dar respuesta inmediata; el servidor sigue siendo la autoridad. El formulario de bindings oculta la routing key en un fanout y explica `*` y `#` en un topic.
- **Confirmaciones:** borrar un exchange o una cola y vaciar una cola piden confirmación con el efecto; eliminar una dead-letter queue en uso lista las colas afectadas, calculadas desde el listado (CA-5.9).
- **Contadores:** botón «Actualizar» y recarga tras cada operación del usuario (CA-8.2, CA-8.8); sin sondeo periódico (CA-8.9).
- **Inspección:** muestra hasta 50 mensajes, el aviso de truncado y, en una dead-letter queue, la cola de origen y el motivo. Incluye el aviso de que la inspección puede marcar los mensajes como reentregados (CA-8.4, R-1).
- **Consumidores:** el flujo se abre al crear el primer consumidor. Cada consumidor muestra sus mensajes recibidos (los últimos 100 en pantalla; no se guardan). En modo manual, tres botones nativos (confirmar, rechazar y reencolar, rechazar sin reencolar) que se deshabilitan al pulsarse; tras el resultado se muestra el desenlace, incluido el aviso «el mensaje se ha descartado».
- **Errores del flujo:** si el flujo se corta, se cierra el `EventSource`, todos los consumidores se marcan como detenidos y se avisa con la opción de reconectar (D-10).
- **Seguridad:** los cuerpos de los mensajes y los nombres se muestran siempre con interpolación de Angular, que los escapa; no se usa `innerHTML` (RNF-5).
- **Accesibilidad:** el estado y los errores van en regiones `aria-live`/`role="alert"`; la lista de mensajes recibidos usa `role="log"`; todos los controles son nativos y activables con teclado (RNF-8).

## 7. Infraestructura y configuración

### 7.1 `infra/docker-compose.yml`
- Servicio **`rabbitmq`**:
  - `image: rabbitmq:4.4-management` y `hostname: rabbitmq` (D-12);
  - puertos `127.0.0.1:${RABBITMQ_PORT:-5672}:5672` y `127.0.0.1:${RABBITMQ_MANAGEMENT_PORT:-15672}:15672` (D-13);
  - `RABBITMQ_DEFAULT_USER` y `RABBITMQ_DEFAULT_PASS` desde `RABBITMQ_USER` y `RABBITMQ_PASSWORD`, con el valor por defecto `guest`;
  - volumen con nombre `rabbitmq-data` montado en `/var/lib/rabbitmq`;
  - comprobación de salud con `rabbitmq-diagnostics -q ping`, solo informativa.
- Servicio **`backend`**: añade `RABBITMQ_HOST=rabbitmq`, `RABBITMQ_PORT=5672` y `RABBITMQ_MANAGEMENT_PORT=15672` (valores internos fijos), más las credenciales. `depends_on: rabbitmq` **sin esperar a que esté sano**, para que el backend arranque aunque RabbitMQ tarde (CL-2).
- Solo RabbitMQ: `docker compose -f infra/docker-compose.yml up -d rabbitmq` (CA-1.2).
- Detener conservando datos: `docker compose -f infra/docker-compose.yml down` (CA-1.3, CA-10.1).
- Empezar de cero: `docker compose -f infra/docker-compose.yml down -v` (CA-10.5).

### 7.2 `infra/.env.example`
Se añaden, comentadas, `RABBITMQ_PORT`, `RABBITMQ_MANAGEMENT_PORT`, `RABBITMQ_USER` y `RABBITMQ_PASSWORD` con sus valores por defecto.

### 7.3 `frontend/nginx.conf`
Un `location = /api/rabbitmq/session/events` propio con `proxy_buffering off`, `proxy_cache off`, `proxy_http_version 1.1`, `Connection ""` y un `proxy_read_timeout` largo, para que los eventos no se retengan. El servidor de desarrollo de Angular ya reenvía el flujo sin cambios.

## 8. Pruebas

**Backend, `./mvnw test`, sin Docker ni RabbitMQ (CA-12.2):**
- Servicios con un gateway falso en memoria: validación de nombres, patrones topic y cuerpos; reglas de dead-letter queue; exchanges predefinidos; bindings por tipo; sesiones de consumidores (nombre único, un pendiente por consumidor manual, doble resolución).
- Controladores con `@WebMvcTest` y servicios simulados, incluido el mapeo de excepciones a `ApiError`.
- Adaptador sin red: traducción de respuestas de la API de gestión (`201`/`204`/`400`), `x-death` y errores, con respuestas HTTP simuladas.

**Backend, `./mvnw verify`, con Docker (CA-12.3):** pruebas `*IT` contra un RabbitMQ real con Testcontainers:
- enrutado de direct, fanout y topic, con `*` y `#`;
- mensaje no enrutable, y publicación en el exchange por defecto con una cola inexistente;
- crear idéntico frente a distinto;
- confirmación, rechazo con reencolado (marcado como reentregado) y rechazo sin reencolar con y sin dead-letter queue, con el origen leído de `x-death`;
- contadores inmediatos tras publicar, inspección sin retirar mensajes y reparto entre dos consumidores;
- cancelación del consumidor al borrar su cola;
- nombres con `/`, `%` y caracteres no ASCII;
- **persistencia:** se detiene y se recrea el contenedor con el mismo hostname y el mismo volumen, y se comprueba que siguen la topología y los mensajes.

**Frontend, `npm test`, sin backend ni RabbitMQ (CA-12.5):** servicios con `HttpTestingController` y un `EventSource` falso; componentes de cada área (carga, éxito, error, reintento, validaciones, confirmaciones, publicación, inspección, consumidor con sus tres acciones y el aviso de descarte, y el guard de salida con confirmar y cancelar); `nav-sections` y el shell actualizados (CA-12.6).

## 9. Documentación y archivos de reglas

- **README.md** (CA-13.1 a CA-13.7): requisitos, arranque con RabbitMQ y solo RabbitMQ, variables nuevas y advertencia de las credenciales por defecto, persistencia y borrado, pruebas de integración, un escenario guiado (exchange, cola con dead-letter queue, binding, publicar, consumir y rechazar), solución de problemas y el riesgo de los ciclos de dead-letter queues.
- **AGENTS.md** (aprobado el 2026-10-30; se aplica como tarea, una vez configurado el plugin de pruebas de integración, para no documentar un comando que todavía no existe): añadir en Comandos `Tests de integración backend: cd backend && ./mvnw verify`, y actualizar la descripción de `infra/` ahora que incluye RabbitMQ.

## 10. Trazabilidad

| Requisito | Dónde se resuelve |
|-----------|-------------------|
| RF-1 | §7.1, §7.2, §5.7 |
| RF-2 | §5.5 (estado), §6.2 (conexión) |
| RF-3 | §6.2 (menú y rutas) |
| RF-4, RF-5, RF-6 | §5.4, §5.5, §6.2 |
| RF-7 | §5.4 (`PublishService`), §5.5 (`AmqpPublisher`) |
| RF-8 | §2 (contadores), §5.5, §6.2 |
| RF-9 | D-8 a D-10, D-17, §5.4, §5.5 (`AmqpConsumers`), §6.2 (consumidores y salida del laboratorio) |
| RF-10 | D-6, D-12, §7.1 |
| RF-11 | D-15, §5.5 (`BrokerErrorMapper`), §6.1 (`core/http`) |
| RF-12 | §5.6, §8 |
| RF-13 | §9 |
| RNF-4 | §6.1 (`request-timeout.ts`), tiempos de espera del backend en §5.5 |
| RNF-5 | §6.2 (seguridad), §5.5 (`RabbitMqProperties`) |
| RNF-7 | D-7, §5.3, §8 |

## 11. Verificación final

1. `cd backend && ./mvnw test` y `cd frontend && npm test` pasan sin Docker ni RabbitMQ.
2. `cd backend && ./mvnw verify` pasa con Docker en marcha.
3. Arranque completo: `docker compose -f infra/docker-compose.yml up -d --build`; en Chrome, el laboratorio muestra «Conectado».
4. Se recorre el escenario guiado: un direct, un fanout y un topic con sus colas y bindings; la publicación llega solo a las colas esperadas; el mensaje sin binding se informa como no enrutado.
5. Con un consumidor manual: confirmar, rechazar y reencolar, y rechazar sin reencolar hacia la dead-letter queue, inspeccionada con su origen y su motivo; en una cola sin dead-letter queue, el mensaje se descarta y se avisa. Con dos consumidores, los mensajes se reparten.
6. Persistencia: `down` y `up` conservan topología y mensajes; `down -v` deja el laboratorio vacío.
7. Con RabbitMQ detenido, la interfaz muestra el error y, al levantarlo, «Reintentar» muestra «Conectado» sin reiniciar el backend. Con un consumidor activo, el corte lo finaliza con aviso.
8. Arranque local: `up -d rabbitmq`, backend con `./mvnw spring-boot:run`, frontend con `npm start`.
9. Puertos de RabbitMQ cambiados en `infra/.env`.
10. Desde un clon limpio, siguiendo solo el README.

## 12. Riesgos y decisiones que necesitan tu respuesta

- **R-1 — La inspección altera el indicador de reentrega.** *Resuelto el 2026-10-30.* Es una limitación de RabbitMQ: no ofrece lectura sin efecto en colas clásicas, y cada inspección deja los mensajes marcados como reentregados. Se añadió la advertencia a CA-8.4 (y CL-25) y la interfaz la muestra junto al resultado de la inspección.
- **R-2 — Credenciales por defecto versionadas.** *Resuelto el 2026-10-30:* se mantienen `guest`/`guest` en `application.properties` y en `docker-compose.yml`, según CA-1.7. Son las credenciales públicas de RabbitMQ y el servicio solo es accesible desde la máquina local (D-13). La documentación lo advierte (CA-13.2).
- **R-3 — Dependencias y plugin nuevos** (§4) y cambio en AGENTS.md (§9). *Aprobados el 2026-10-30.* Se incorporan como tareas, no antes.
- **R-4 — Nombres con caracteres especiales.** Se mitiga con parámetros de consulta (D-11) y pruebas con `/`, `%` y caracteres no ASCII. Si alguna ruta del cliente HTTP los codificara mal, se detectará en las pruebas de integración.
- **R-5 — Persistencia tras recrear el contenedor.** Depende de que el hostname y el volumen sean estables (D-12). La prueba de persistencia de §8 y el paso 6 de §11 lo verifican.
- **R-6 — Flujo SSE a través de nginx.** Si el almacenamiento en búfer retiene los eventos, los mensajes no aparecerían en tiempo real. Se mitiga con §7.3 y se verifica en Chrome contra el arranque completo.
- **R-7 — Las pruebas de integración necesitan Docker.** `./mvnw verify` falla sin Docker en marcha; `./mvnw test` no. El README lo indica.
- **R-8 — Tamaño de la funcionalidad.** Es la spec más grande hasta ahora; `tasks.md` la dividirá en fases (infraestructura, backend por áreas, frontend por áreas, consumidores, validación) para que cada tarea siga siendo de 20-30 minutos.
- **R-9 — Versiones.** `amqp-client` queda en la 5.30.0 que gestiona Spring Boot 4.1.2, aunque exista una posterior. Al actualizar Spring Boot o el generador de OpenAPI, hay que repasar el mapeo a `SseEmitter` (D-8).
- **R-10 — Salir del laboratorio detiene los consumidores.** *Resuelto el 2026-10-30:* se mantiene, pero con confirmación previa. Se añadió CA-9.14 y CL-24 a la spec para que el comportamiento esté cubierto (§6.2).

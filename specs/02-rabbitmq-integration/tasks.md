# Tareas 02 — Integración con RabbitMQ

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Las tareas están en orden de dependencia. Cada una debe completarse, con su comprobación «Hecho cuando» superada, antes de empezar la siguiente. Una tarea solo se marca cuando su comprobación se ha ejecutado y se ha comprobado el resultado.

Convenciones:
- Cada área del backend sigue el mismo ciclo: contrato → servicio con sus reglas → adaptador → controlador → prueba de integración con RabbitMQ real.
- Las pruebas `*IT` necesitan Docker en marcha y se ejecutan con `cd backend && ./mvnw verify`; `./mvnw test` no las ejecuta ni necesita Docker.
- «Regresión» significa que siguen pasando las pruebas ya existentes del módulo.

## Fase 0 — Dependencias y base de pruebas de integración

- [x] **T01 — Dependencia del cliente AMQP**
  Añadir `com.rabbitmq:amqp-client` al `pom.xml` sin versión explícita, para que la fije el BOM de Spring Boot (plan §4).
  *RF:* RF-7, RF-9 (RNF-6)
  *Hecho cuando:* `./mvnw dependency:tree` muestra `amqp-client` en la versión 5.30.0 y `./mvnw test` pasa (regresión).

- [x] **T02 — Plugin de integración y dependencias de Testcontainers**
  Añadir `testcontainers-rabbitmq` y `testcontainers-junit-jupiter` (ámbito de pruebas, versión del BOM) y configurar `maven-failsafe-plugin` para ejecutar las clases `*IT` en `verify`, dejando `test` solo con las unitarias.
  *RF:* RF-12 (CA-12.2, CA-12.3)
  *Hecho cuando:* `./mvnw dependency:tree` muestra ambas dependencias en la 2.0.5 y `./mvnw test` pasa sin ejecutar ninguna clase `*IT`.

- [x] **T03 — Soporte común de las pruebas `*IT` y prueba de humo**
  Crear en las pruebas una clase de soporte que levante `rabbitmq:4.4-management` con Testcontainers, y una `RabbitMqSmokeIT` que consulte la API de gestión y compruebe que responde.
  *RF:* RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` (con Docker en marcha) ejecuta `RabbitMqSmokeIT` y pasa, y `./mvnw test` sigue sin ejecutarla.

- [x] **T04 — Comando de integración en AGENTS.md**
  Añadir a la sección de comandos de [AGENTS.md](../../AGENTS.md) `Tests de integración backend: cd backend && ./mvnw verify` (aprobado en el plan §9).
  *RF:* RF-12, RF-13
  *Hecho cuando:* el comando figura en AGENTS.md y se ha ejecutado en T03 con resultado correcto.

## Fase 1 — Infraestructura

- [x] **T05 — Servicio RabbitMQ en Compose y variables**
  Añadir a `infra/docker-compose.yml` el servicio `rabbitmq` (imagen `rabbitmq:4.4-management`, `hostname: rabbitmq`, volumen con nombre `rabbitmq-data`, puertos `127.0.0.1:${RABBITMQ_PORT:-5672}:5672` y `127.0.0.1:${RABBITMQ_MANAGEMENT_PORT:-15672}:15672`, credenciales con valor por defecto `guest`, comprobación de salud). Añadir a `infra/.env.example` las cuatro variables comentadas.
  *RF:* RF-1 (CA-1.1, CA-1.2, CA-1.4 a CA-1.8)
  *Hecho cuando:* `docker compose -f infra/docker-compose.yml up -d rabbitmq` deja el servicio sano, `curl -u guest:guest localhost:15672/api/overview` devuelve 200, `lsof` muestra los puertos 5672 y 15672 escuchando solo en `127.0.0.1`, y con `RABBITMQ_PORT=5673` y `RABBITMQ_MANAGEMENT_PORT=15673` en `infra/.env` los puertos cambian.

- [x] **T06 — Persistencia y reinicio de la infraestructura**
  Verificar la persistencia a nivel de infraestructura: crear por la API de gestión una cola durable y un mensaje persistente, ejecutar `down` y `up`, y comprobar que siguen ahí; ejecutar `down -v` y comprobar que desaparecen.
  *RF:* RF-1 (CA-1.3), RF-10 (CA-10.1, CA-10.2, CA-10.5, CA-10.6)
  *Hecho cuando:* tras `down` y `up` la cola y el mensaje existen; tras `down -v` y `up` la cola no existe; y se anota el resultado.

- [x] **T07 — Backend en Compose con RabbitMQ**
  Añadir al servicio `backend` las variables `RABBITMQ_HOST=rabbitmq`, `RABBITMQ_PORT=5672`, `RABBITMQ_MANAGEMENT_PORT=15672` y las credenciales, y `depends_on: rabbitmq` sin esperar a que esté sano.
  *RF:* RF-1 (CA-1.1), RF-11 (CL-2)
  *Hecho cuando:* `docker compose -f infra/docker-compose.yml config` muestra las variables y la dependencia sin condición de salud, y la aplicación completa sigue arrancando con `up -d --build`.

## Fase 2 — Fundamentos del backend

- [x] **T08 — Propiedades de RabbitMQ**
  Crear `RabbitMqProperties` (host, puerto AMQP, puerto de gestión, usuario, contraseña y virtual host) enlazada a `messaginglab.rabbitmq.*` en `application.properties`, leyendo `RABBITMQ_*` con los valores por defecto del plan §5.7. La contraseña no aparece en `toString`.
  *RF:* RF-1 (CA-1.5, CA-1.7), RF-12 (RNF-5)
  *Hecho cuando:* una prueba unitaria comprueba los valores por defecto, que una variable los sustituye y que `toString` no contiene la contraseña.

- [x] **T09 — Excepciones de dominio y respuesta de error**
  Añadir al contrato `ApiError { code, message }` con los códigos del plan §5.6. Crear las excepciones de dominio en `exception` y un `@RestControllerAdvice` que las traduzca a `ApiError` con mensaje en español y el estado HTTP adecuado.
  *RF:* RF-11 (CA-11.3, CA-11.4), RF-12 (CA-12.1)
  *Hecho cuando:* una prueba comprueba, para cada excepción, el estado, el código y un mensaje en español sin trazas, y `./mvnw compile` genera `ApiError`.

- [x] **T10 — Validadores de nombre y de patrón**
  Crear `NameValidator` (no vacío, 255 caracteres y 255 bytes UTF-8, sin caracteres de control, sin prefijo `amq.`) y `TopicPatternValidator` (palabras separadas por puntos con `*` y `#`, hasta 255 caracteres), con mensajes en español.
  *RF:* RF-4 (CA-4.4), RF-5 (CA-5.4), RF-6 (CA-6.4, CA-6.5)
  *Hecho cuando:* las pruebas cubren nombre vacío, de 255 y 256, multibyte que supera 255 bytes, con caracteres de control, con `amq.`, patrones válidos e inválidos y la routing key vacía.

- [x] **T11 — Modelo de dominio y puertos del gateway**
  Crear los records y enumerados de `model` (plan §5.2) y las interfaces `BrokerTopology`, `BrokerPublisher` y `BrokerConsumers` de `gateway` (plan §5.3).
  *RF:* RF-12 (RNF-7)
  *Hecho cuando:* `./mvnw test` pasa y una búsqueda confirma que ningún archivo de `model`, `service`, `exception` ni `gateway` (salvo `gateway/rabbitmq`) importa `com.rabbitmq` ni las clases generadas.

- [x] **T12 — Cliente HTTP de gestión y traducción de errores**
  Crear `ManagementApiClient` base (`RestClient` con la URL, autenticación básica y tiempos de espera de 10 s) y `BrokerErrorMapper`, que traduce estados HTTP y fallos de conexión a las excepciones de dominio.
  *RF:* RF-11 (CA-11.1, CA-11.3, CA-11.4)
  *Hecho cuando:* pruebas con `MockRestServiceServer` verifican que 401 da el mensaje de credenciales rechazadas sin revelarlas, que 404 da no encontrado, que 400 da conflicto con su motivo, que un fallo de conexión da «RabbitMQ no disponible» y que un tiempo agotado da el error de tiempo.

- [x] **T13 — Estado de la conexión: contrato, servicio y adaptador**
  Añadir `GET /api/rabbitmq/status` al contrato, crear `ConnectionStatusService` y la llamada de resumen (`/overview`) del adaptador.
  *RF:* RF-2 (CA-2.1, CA-2.3, CA-2.4), RF-12 (CA-12.1)
  *Hecho cuando:* pruebas del servicio con un gateway falso y del adaptador con `MockRestServiceServer` pasan para conectado, inalcanzable y credenciales rechazadas; y `./mvnw compile` genera la interfaz.

- [x] **T14 — Estado de la conexión: controlador**
  Crear el controlador de estado, que implementa la interfaz generada.
  *RF:* RF-2
  *Hecho cuando:* una prueba `@WebMvcTest` verifica la respuesta 200 con el estado conectado y la 503 con `ApiError` cuando el servicio lanza «no disponible».

## Fase 3 — Exchanges

- [x] **T15 — Exchanges: contrato**
  Añadir al contrato `GET`/`POST /exchanges` y `DELETE /exchanges?name=`, con los esquemas `Exchange`, `ExchangeType`, `CreateExchangeRequest` y `ExchangeCreationResponse` (`created`).
  *RF:* RF-4, RF-12 (CA-12.1)
  *Hecho cuando:* `./mvnw compile` genera `ExchangesApi` y los modelos.

- [x] **T16 — Exchanges: servicio y reglas**
  Crear `ExchangeService` y un fake en memoria de la parte de exchanges de `BrokerTopology` para las pruebas. Reglas: validación de nombre, tipo permitido, `predefined` para el exchange por defecto y los `amq.*`, y rechazo de borrar o modificar los predefinidos.
  *RF:* RF-4 (CA-4.1 a CA-4.5, CA-4.7)
  *Hecho cuando:* las pruebas unitarias cubren creación, repetición idéntica («ya existe»), repetición con otro tipo (rechazo), nombres inválidos, borrado y borrado de un predefinido.

- [x] **T17 — Exchanges: adaptador**
  Implementar en el adaptador listar, crear y borrar exchanges: `PUT` con `durable: true`, distinción `201`/`204` para «creado» frente a «ya existía», conflicto en `400`, e indicador de predefinido.
  *RF:* RF-4, RF-10 (CA-10.1)
  *Hecho cuando:* pruebas con `MockRestServiceServer` verifican las peticiones (incluida la codificación de un nombre con `/` y `%`), los estados `201`, `204` y `400` y el indicador de predefinido.

- [x] **T18 — Exchanges: controlador**
  Crear `ExchangesController`.
  *RF:* RF-4
  *Hecho cuando:* pruebas `@WebMvcTest` verifican el listado, la creación (`created` verdadero y falso), el borrado y la traducción de los errores de validación y de conflicto.

- [x] **T19 — Exchanges: prueba de integración**
  Escribir `ExchangesIT` contra un RabbitMQ real.
  *RF:* RF-4, RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa con casos de creación de los tres tipos, repetición idéntica, repetición con otro tipo, borrado, exchange predefinido no borrable y un nombre con `/`, `%` y caracteres no ASCII.

## Fase 4 — Colas

- [x] **T20 — Colas: contrato**
  Añadir al contrato `GET`/`POST /queues` y `DELETE /queues?name=`, con `Queue` (contadores y dead-letter queue) y `CreateQueueRequest` (`name`, `deadLetterQueue` opcional).
  *RF:* RF-5, RF-12 (CA-12.1)
  *Hecho cuando:* `./mvnw compile` genera `QueuesApi` y los modelos. *(Nota: la interfaz generada es `RabbitMqQueuesApi`, por la etiqueta `RabbitMqQueues`.)*

- [x] **T21 — Colas: servicio y reglas**
  Crear `QueueService` y extender el fake con las colas. Reglas: validación de nombre, la dead-letter queue debe existir, una cola no puede ser su propia dead-letter queue, y el borrado.
  *RF:* RF-5 (CA-5.1, CA-5.2, CA-5.4 a CA-5.6, CA-5.9)
  *Hecho cuando:* las pruebas unitarias cubren creación con y sin dead-letter queue, dead-letter queue inexistente, cola como su propia dead-letter queue, repetición idéntica y con otra configuración, nombres inválidos y borrado.

- [x] **T22 — Colas: adaptador**
  Implementar listar (con el listado de contadores `disable_stats=true&enable_queue_totals=true`), crear (`durable`, `x-queue-type: classic` y, si procede, `x-dead-letter-exchange: ""` con `x-dead-letter-routing-key`), borrar y vaciar (`DELETE …/contents`).
  *RF:* RF-5, RF-10 (CA-10.1), RF-8 (CA-8.1)
  *Hecho cuando:* pruebas con `MockRestServiceServer` verifican el cuerpo de creación, la lectura de la dead-letter queue a partir de los argumentos, los contadores y los estados `201`, `204` y `400`.

- [x] **T23 — Colas: controlador**
  Crear `QueuesController` con listar, crear y borrar. *(Nota: la clase se llama `RabbitMqQueuesController`, como `RabbitMqExchangesController`; implementa `RabbitMqQueuesApi`.)*
  *RF:* RF-5
  *Hecho cuando:* pruebas `@WebMvcTest` verifican listado con contadores, creación, borrado y errores.

- [x] **T24 — Colas: prueba de integración**
  Escribir `QueuesIT` contra un RabbitMQ real.
  *RF:* RF-5, RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa con casos de creación, dead-letter queue leída de vuelta, repetición idéntica y con otra configuración, borrado, cola que es dead-letter queue de otra y nombre con caracteres especiales.

## Fase 5 — Bindings

- [x] **T25 — Bindings: contrato**
  Añadir al contrato `GET /bindings?exchange=&queue=`, `POST /bindings` y `DELETE /bindings?exchange=&queue=&routingKey=`, con `Binding` y `CreateBindingRequest`.
  *RF:* RF-6, RF-12 (CA-12.1)
  *Hecho cuando:* `./mvnw compile` genera `BindingsApi` y los modelos. *(Nota: la interfaz generada es `RabbitMqBindingsApi`.)*

- [x] **T26 — Bindings: servicio y reglas**
  Crear `BindingService` y extender el fake con los bindings. Reglas: el exchange y la cola deben existir, el exchange por defecto no admite bindings, el fanout ignora la routing key, el topic valida el patrón, límite de 255 caracteres y detección de binding idéntico.
  *RF:* RF-6 (CA-6.2 a CA-6.9)
  *Hecho cuando:* las pruebas unitarias cubren cada tipo de exchange, patrones válidos e inválidos, routing key vacía, exchange por defecto, varios bindings de una cola, binding repetido («ya existe») y borrado.

- [x] **T27 — Bindings: adaptador**
  Implementar listar (omitiendo los implícitos del exchange por defecto), crear (consultando antes los existentes del par) y borrar (con la clave del binding; la routing key vacía se codifica como `~`).
  *RF:* RF-6, RF-10 (CA-10.1)
  *Hecho cuando:* pruebas con `MockRestServiceServer` verifican el filtrado de implícitos, la detección de repetidos, la codificación de la clave de borrado (incluida `a.#` y la vacía) y las peticiones.

- [x] **T28 — Bindings: controlador**
  Crear `BindingsController`. *(Nota: la clase se llama `RabbitMqBindingsController`.)*
  *RF:* RF-6
  *Hecho cuando:* pruebas `@WebMvcTest` verifican listado con filtros, creación (creado y ya existente), borrado y errores.

- [x] **T29 — Bindings: prueba de integración**
  Escribir `BindingsIT` contra un RabbitMQ real.
  *RF:* RF-6, RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa con casos de creación en los tres tipos, binding repetido, varias routing keys para una cola, borrado de uno con routing key vacía y otro con patrón, y exchange por defecto rechazado.

## Fase 6 — Publicación y enrutado

- [x] **T30 — Publicación: contrato**
  Añadir al contrato `POST /publications` con `PublishRequest` y `PublishResponse` (`routed`).
  *RF:* RF-7, RF-12 (CA-12.1)
  *Hecho cuando:* `./mvnw compile` genera `PublicationsApi` y los modelos. *(Nota: la interfaz generada es `RabbitMqPublicationsApi`.)*

- [x] **T31 — Publicación: servicio y reglas**
  Crear `PublishService` con un fake de `BrokerPublisher`. Reglas: cuerpo no vacío y de hasta 64 KB en UTF-8, exchange existente, routing key de hasta 255 caracteres y publicación en el exchange por defecto con el nombre de una cola.
  *RF:* RF-7 (CA-7.1, CA-7.5 a CA-7.7)
  *Hecho cuando:* las pruebas unitarias cubren cuerpo vacío, de 64 KB y de 64 KB más un byte, multibyte, exchange inexistente y publicación en el exchange por defecto.

- [x] **T32 — Conexión AMQP**
  Crear `AmqpConnectionProvider`: conexión compartida creada de forma perezosa, recreada si está cerrada, **sin recuperación automática**, con tiempo de conexión de 5 s.
  *RF:* RF-11 (CA-11.2), RF-1 (CL-2)
  *Hecho cuando:* una prueba `*IT` comprueba que obtiene una conexión abierta, que tras cerrarla obtiene otra nueva, y que con RabbitMQ detenido lanza «no disponible» sin bloquear más de 5 s.

- [x] **T33 — Publicación: adaptador AMQP**
  Implementar `AmqpPublisher`: canal con confirmaciones, `mandatory=true`, receptor de devoluciones, mensaje persistente en texto UTF-8, espera de la confirmación de hasta 10 s.
  *RF:* RF-7 (CA-7.2, CA-7.3, CA-7.4), RF-10 (CA-10.2)
  *Hecho cuando:* una `*IT` verifica que se obtiene `routed=true` con un binding y `routed=false` sin él, y que el mensaje publicado tiene `delivery_mode` 2 al leerlo por la API de gestión.

- [x] **T34 — Publicación: controlador**
  Crear `PublicationsController`. *(Nota: la clase se llama `RabbitMqPublicationsController`.)*
  *RF:* RF-7
  *Hecho cuando:* pruebas `@WebMvcTest` verifican 200 con `routed` verdadero y falso, el rechazo por cuerpo inválido y la traducción de «no disponible».

- [x] **T35 — Enrutado direct, fanout y exchange por defecto: integración**
  Escribir `RoutingIT` con direct, fanout y exchange por defecto.
  *RF:* RF-7, RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa comprobando, con los contadores, que direct entrega solo a la cola con la routing key exacta, que fanout entrega a todas, que el exchange por defecto entrega a la cola cuyo nombre es la routing key y que un nombre inexistente da `routed=false`.

- [x] **T36 — Enrutado topic: integración**
  Añadir a `RoutingIT` los casos de topic con `*` y `#`.
  *RF:* RF-6 (CA-6.4), RF-7, RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa con patrones `a.*`, `a.#`, `#` y `*.b.*`, y routing keys que coinciden y que no coinciden.

## Fase 7 — Contadores, inspección y vaciado

- [x] **T37 — Inspección y vaciado: contrato**
  Añadir al contrato `GET /queues/messages?queue=` (inspección) y `DELETE /queues/messages?queue=` (vaciado), con `QueueMessage` y `QueueInspection`.
  *RF:* RF-8, RF-5 (CA-5.8), RF-12 (CA-12.1)
  *Hecho cuando:* `./mvnw compile` genera las operaciones y los modelos.

- [x] **T38 — Inspección y vaciado: servicio**
  Crear `QueueInspectionService` y extender el fake. Reglas: máximo de 50 mensajes, indicador de truncado y total, cola existente, y vaciado solo de los mensajes listos.
  *RF:* RF-8 (CA-8.3, CA-8.6), RF-5 (CA-5.8)
  *Hecho cuando:* las pruebas unitarias cubren cola vacía, 50 mensajes, 51 mensajes (truncado y total), cola inexistente y vaciado.

- [x] **T39 — Inspección: adaptador**
  Implementar `POST …/get` con `ackmode=ack_requeue_true`, `count=50` y `encoding=auto`, y el mapeo de los mensajes de una dead-letter queue: exchange y routing key originales de `x-death`, y cola de origen y motivo de `x-first-death-queue` y `x-first-death-reason`.
  *RF:* RF-8 (CA-8.3 a CA-8.5)
  *Hecho cuando:* pruebas con `MockRestServiceServer`, usando respuestas reales capturadas en la comprobación previa del plan §2, verifican el mapeo de un mensaje normal, uno reentregado y uno de dead-letter queue.

- [x] **T40 — Inspección y vaciado: controlador**
  Crear el controlador de mensajes de una cola. *(Nota: `RabbitMqQueueMessagesController`, que implementa `RabbitMqQueueMessagesApi` (etiqueta propia para no mezclarse con `RabbitMqQueuesApi`).)*
  *RF:* RF-8, RF-5 (CA-5.8)
  *Hecho cuando:* pruebas `@WebMvcTest` verifican inspección, vaciado y errores.

- [x] **T41 — Contadores, inspección y vaciado: integración**
  Escribir `InspectionIT` contra un RabbitMQ real.
  *RF:* RF-8, RF-5 (CA-5.8), RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa comprobando que los contadores reflejan una publicación al instante, que inspeccionar no retira mensajes, que una segunda inspección los devuelve marcados como reentregados (CL-25), que el vaciado elimina solo los listos y que 51 mensajes dan truncado con total.

## Fase 8 — Consumidores en el backend

- [x] **T42 — Consumidores y flujo SSE: contrato**
  Añadir al contrato `GET /session/events` (`text/event-stream`), `POST`/`DELETE` de consumidores y `POST` de resolución de mensaje, con los esquemas de eventos y de acción. Configurar `schemaMappings` de `ConsumerEventStream` a `SseEmitter` en el generador.
  *RF:* RF-9, RF-12 (CA-12.1)
  *Hecho cuando:* `./mvnw compile` genera la interfaz con `ResponseEntity<SseEmitter>` para el flujo y el resto de operaciones.

- [x] **T43 — Consumidores: sesiones y ciclo de vida**
  Crear `ConsumerSessionService` con un fake de `BrokerConsumers`: registro de sesiones, alta y baja de consumidores, nombre único, modo de confirmación, y detención de todos los consumidores al cerrar la sesión.
  *RF:* RF-9 (CA-9.1, CA-9.8, CA-9.11, CA-9.13)
  *Hecho cuando:* las pruebas unitarias cubren alta, nombre repetido, varios consumidores en una cola, baja, cierre de sesión que detiene todos y sesión inexistente.

- [x] **T44 — Consumidores: reglas de resolución de mensajes**
  Añadir al servicio la resolución de mensajes: un único pendiente por consumidor manual, confirmar, rechazar y reencolar, rechazar sin reencolar (con resultado `DEAD_LETTERED` si la cola tiene dead-letter queue o `DISCARDED` si no), y resolución única.
  *RF:* RF-9 (CA-9.4 a CA-9.7, CA-9.10, CA-9.12)
  *Hecho cuando:* las pruebas unitarias cubren las tres acciones, ambos desenlaces del rechazo, doble resolución (conflicto) y mensaje o consumidor inexistentes.

- [x] **T45 — Consumidores: adaptador, inicio, parada y modo automático**
  Implementar `AmqpConsumers`: canal por consumidor, `basicConsume` en modo automático, entrega al receptor y parada que cierra el canal.
  *RF:* RF-9 (CA-9.1 a CA-9.3, CA-9.8)
  *Hecho cuando:* una `*IT` verifica que un consumidor automático recibe los mensajes publicados con su cuerpo, exchange, routing key y reentrega, y que tras detenerlo la cola no tiene consumidores.

- [x] **T46 — Consumidores: adaptador, modo manual**
  Añadir el modo manual: `basicQos(1)`, confirmar, rechazar con reencolado y rechazar sin reencolado.
  *RF:* RF-9 (CA-9.4 a CA-9.8, CA-9.12)
  *Hecho cuando:* una `*IT` verifica que solo se entrega un mensaje hasta resolverlo; que confirmar lo elimina; que reencolar lo devuelve marcado como reentregado; que rechazar sin reencolar lo lleva a la dead-letter queue con su origen en `x-death` y que sin dead-letter queue lo descarta; y que detener el consumidor devuelve el pendiente a la cola.

- [x] **T47 — Consumidores: cancelación y pérdida de conexión**
  Traducir la cancelación del consumidor (cola borrada) y el cierre del canal o de la conexión a los motivos `QUEUE_DELETED` y `CONNECTION_LOST`.
  *RF:* RF-9 (CA-9.9), RF-11 (CL-3, CL-15)
  *Hecho cuando:* una `*IT` verifica que al borrar la cola el receptor recibe `QUEUE_DELETED`, y que al detener el contenedor de RabbitMQ lo recibe con `CONNECTION_LOST` y los pendientes vuelven a la cola al recuperarse.

- [x] **T48 — Flujo SSE de la sesión**
  Crear el controlador del flujo `GET /session/events` y su gestión: `SseEmitter` sin tiempo de expiración, evento `session-started` con el identificador, envío serializado de los eventos `consumer-started`, `message-received` y `consumer-stopped`, latido de comentario cada 15 s y cierre de la sesión (con detención de sus consumidores) al terminar, fallar o expirar el emisor.
  *RF:* RF-9 (CA-9.2, CA-9.13), RF-11
  *Hecho cuando:* pruebas unitarias con un emisor falso verifican el formato de cada evento, el latido y que el cierre del emisor detiene los consumidores de esa sesión y no los de otra.

- [x] **T49 — Consumidores: controlador de operaciones**
  Crear los controladores para crear y detener un consumidor y para resolver un mensaje, incluido el resultado de la acción. *(Nota: `RabbitMqConsumersController`, con la interfaz `RabbitMqConsumersApi`; el flujo SSE es `RabbitMqSessionEventsController`, con `RabbitMqSessionEventsApi`.)*
  *RF:* RF-9
  *Hecho cuando:* pruebas `@WebMvcTest` verifican alta (con nombre repetido en conflicto), baja, las tres acciones con su desenlace, el conflicto por doble resolución y los errores de sesión o consumidor inexistentes.

- [x] **T50 — Consumidores: integración de extremo a extremo por HTTP**
  Escribir `ConsumersIT` con `@SpringBootTest(webEnvironment = RANDOM_PORT)`, un RabbitMQ real y un cliente HTTP que lea el flujo SSE.
  *RF:* RF-9, RF-12 (CA-12.3)
  *Hecho cuando:* `./mvnw verify` pasa comprobando: apertura del flujo y recepción de `session-started`; alta de un consumidor y recepción de un mensaje publicado; confirmación; reparto entre dos consumidores de la misma cola; y que **cerrar el flujo devuelve a la cola el mensaje pendiente**.

## Fase 9 — Frontend: base del laboratorio

- [x] **T51 — Utilidades HTTP compartidas**
  Crear `core/http/request-timeout.ts` (20 s) y `core/http/api-error.ts` (traducción de `ApiError`, tiempo agotado, estado 0 y 502/503/504 a un mensaje en español). Hacer que `StatusApiService` use el tiempo compartido.
  *RF:* RF-11 (CA-11.1, CA-11.3, CA-11.4), RF-12 (CA-12.4)
  *Hecho cuando:* `npm test -- --watch=false` pasa con pruebas de la traducción de cada caso, y las pruebas existentes de `StatusApiService` siguen pasando.

- [x] **T52 — Diálogo de confirmación**
  Crear `shared/confirm-dialog` con `<dialog>` nativo: título, aviso, lista opcional de elementos afectados, confirmar y cancelar.
  *RF:* RF-4 (CA-4.6), RF-5 (CA-5.7), RF-12 (CA-12.4) (RNF-8)
  *Hecho cuando:* las pruebas verifican que se abre con el contenido, que confirmar y cancelar devuelven el resultado, que se cierra con Escape y que los botones son operables con teclado.

- [x] **T53 — Estado de la conexión en la interfaz**
  Crear `RabbitMqConnection` con una señal de tres estados (cargando, conectado, no disponible con mensaje) y las acciones de consultar y reintentar, que impide un reintento durante otro. Si es el propio backend el que no responde, el comportamiento es el del RF-3 de la especificación 01 (mensaje de backend no disponible y reintento).
  *RF:* RF-2 (CA-2.1 a CA-2.8)
  *Hecho cuando:* `npm test -- --watch=false` pasa con pruebas de carga, conectado, RabbitMQ no disponible, backend que no responde (mensaje del backend, no el de RabbitMQ), reintento con éxito y reintento bloqueado mientras hay uno en curso.

- [x] **T54 — Contenedor del laboratorio y rutas**
  Crear `rabbitmq-lab` con el estado de la conexión, el aviso y el botón de reintento, las pestañas de las cinco áreas y el `<router-outlet>`, y las rutas hijas de `/rabbitmq`. Las áreas, de momento, son marcadores.
  *RF:* RF-2 (CA-2.4), RF-3 (CA-3.3, CA-3.4)
  *Hecho cuando:* las pruebas verifican que con la conexión no disponible se muestra el mensaje y las pestañas indican que las operaciones están deshabilitadas, que se puede navegar entre las cinco áreas y que `/rabbitmq` redirige a exchanges; y `npx ng build` pasa.

- [x] **T55 — Menú: habilitar el laboratorio y actualizar las pruebas de la 01**
  En `nav-sections.ts`, habilitar «Laboratorio RabbitMQ» con la ruta `/rabbitmq` y dejar deshabilitadas «Laboratorio Kafka» y «Visualizador de flujo». Actualizar `shell.spec` y los demás tests de la 01 que lo asumían.
  *RF:* RF-3 (CA-3.1, CA-3.2, CA-3.4), RF-12 (CA-12.6)
  *Hecho cuando:* `npm test -- --watch=false` pasa completo (regresión incluida), y las pruebas verifican que «Laboratorio RabbitMQ» es un enlace activo a `/rabbitmq` y que las otras dos siguen deshabilitadas con su aviso.

## Fase 10 — Frontend: áreas del laboratorio

- [x] **T56 — Exchanges: servicio**
  Crear el servicio de exchanges (listar, crear, borrar) con tipos de la API y errores traducidos.
  *RF:* RF-4, RF-11
  *Hecho cuando:* pruebas con `HttpTestingController` verifican las peticiones (el nombre en parámetro de consulta), la respuesta de creación con `created` y los errores.

- [x] **T57 — Exchanges: pantalla**
  Crear la vista de exchanges: listado con tipo y marca de predefinido, formulario reactivo (nombre y tipo) con validaciones, aviso «ya existe», y borrado con el diálogo de confirmación que avisa de los bindings. Sin acciones para los predefinidos.
  *RF:* RF-4 (CA-4.1 a CA-4.7), RF-11 (CA-11.5, CA-11.6) (RNF-8)
  *Hecho cuando:* las pruebas verifican listado, validaciones del formulario, creación, mensaje de «ya existe», error mostrado sin perder el listado, confirmación de borrado, ausencia de borrado en los predefinidos y botón deshabilitado durante la operación.

- [x] **T58 — Colas: servicio**
  Crear el servicio de colas (listar con contadores, crear, borrar, vaciar).
  *RF:* RF-5, RF-11
  *Hecho cuando:* pruebas con `HttpTestingController` verifican las peticiones y los errores.

- [x] **T59 — Colas: listado y creación**
  Crear la vista de colas con el listado (listos, pendientes, total y dead-letter queue), el botón «Actualizar», el formulario con selector de dead-letter queue entre las colas existentes, y la recarga tras cada operación.
  *RF:* RF-5 (CA-5.1 a CA-5.6), RF-8 (CA-8.1, CA-8.2, CA-8.8, CA-8.9)
  *Hecho cuando:* las pruebas verifican contadores, actualización manual, recarga tras crear, validaciones, aviso de «ya existe», y que no hay actualización periódica.

- [x] **T60 — Colas: borrar y vaciar con confirmación**
  Añadir borrar y vaciar con el diálogo de confirmación. Al borrar una cola que es dead-letter queue de otras, el diálogo lista las colas afectadas y avisa del descarte.
  *RF:* RF-5 (CA-5.7 a CA-5.9)
  *Hecho cuando:* las pruebas verifican el diálogo de borrado, la lista de colas afectadas, el diálogo de vaciado y que cancelar no llama al servicio.

- [x] **T61 — Bindings: servicio**
  Crear el servicio de bindings (listar con filtros, crear, borrar).
  *RF:* RF-6, RF-11
  *Hecho cuando:* pruebas con `HttpTestingController` verifican las peticiones, el borrado con routing key vacía y los errores.

- [x] **T62 — Bindings: pantalla**
  Crear la vista de bindings: listado filtrable por exchange o cola, formulario que oculta la routing key en un fanout y explica `*` y `#` en un topic, aviso de «ya existe» y borrado sin confirmación.
  *RF:* RF-6 (CA-6.1 a CA-6.9)
  *Hecho cuando:* las pruebas verifican el formulario según el tipo de exchange, la validación del patrón, la creación, el aviso de repetido, el borrado y la exclusión del exchange por defecto.

- [x] **T63 — Publicación: servicio y pantalla**
  Crear el servicio y la vista de publicación: exchange, routing key (según tipo), cuerpo, resultado «enrutado» o «no enrutado y descartado», conservación del contenido ante un error y botón bloqueado durante el envío.
  *RF:* RF-7 (CA-7.1, CA-7.3 a CA-7.8)
  *Hecho cuando:* las pruebas verifican publicación correcta, resultado no enrutado, validación del cuerpo vacío y de más de 64 KB, error con el contenido conservado, publicación en el exchange por defecto y botón deshabilitado durante la operación.

- [x] **T64 — Inspección: servicio y panel**
  Crear la llamada de inspección y el panel de mensajes de una cola: cuerpo escapado, exchange, routing key, reentrega, aviso de truncado con el total, origen y motivo en una dead-letter queue, y el aviso de que la inspección puede marcar los mensajes como reentregados.
  *RF:* RF-8 (CA-8.3 a CA-8.7) (RNF-5)
  *Hecho cuando:* las pruebas verifican la lista, la cola vacía, el truncado, el origen de dead-letter queue, el aviso de reentrega y que un cuerpo con `<script>` se muestra como texto.

## Fase 11 — Frontend: consumidores

- [x] **T65 — Sesión de consumidores: servicio y flujo**
  Crear `ConsumerSessionService`, provisto en la ruta `/rabbitmq`, con una fábrica inyectable de `EventSource` para poder probarlo. Abre el flujo al crear el primer consumidor, procesa los cuatro eventos y expone consumidores y mensajes en señales (últimos 100 por consumidor).
  *RF:* RF-9 (CA-9.1, CA-9.2, CA-9.11)
  *Hecho cuando:* las pruebas, con un `EventSource` falso, verifican apertura del flujo, alta y baja de consumidores, recepción de mensajes, el límite de 100 y el cierre del flujo al destruir el servicio.

- [x] **T66 — Consumidores: pantalla**
  Crear la vista de consumidores: formulario (cola, modo, nombre), lista de consumidores activos y registro de mensajes recibidos con `role="log"`, con cuerpo escapado, exchange, routing key y reentrega.
  *RF:* RF-9 (CA-9.1, CA-9.2, CA-9.11), RF-12 (CA-12.4) (RNF-8)
  *Hecho cuando:* las pruebas verifican el alta, varios consumidores en una cola, la recepción visible de mensajes, la detención y los mensajes que se conservan al navegar a otra área del laboratorio.

- [x] **T67 — Consumidores: acciones sobre un mensaje**
  Añadir al modo manual los tres botones nativos (confirmar, rechazar y reencolar, rechazar sin reencolar), que se deshabilitan al pulsarse, y mostrar el desenlace, incluido «el mensaje se ha descartado».
  *RF:* RF-9 (CA-9.4 a CA-9.7, CA-9.10)
  *Hecho cuando:* las pruebas verifican cada acción con su desenlace, el aviso de descarte, el bloqueo de una segunda acción y el error por doble resolución.

- [x] **T68 — Consumidores: confirmación al salir del laboratorio**
  Añadir el guard `canDeactivate` de la ruta `/rabbitmq`: con consumidores activos abre el diálogo; confirmar los detiene y permite salir, cancelar anula la navegación. No actúa al cambiar entre rutas hijas.
  *RF:* RF-9 (CA-9.14)
  *Hecho cuando:* las pruebas verifican que salir con consumidores pide confirmación, que confirmar detiene y navega, que cancelar permanece, que sin consumidores no pregunta y que cambiar de área no pregunta.

- [x] **T69 — Consumidores: errores del flujo y reconexión**
  Gestionar el corte del flujo: cerrar el `EventSource` sin reconexión automática, marcar todos los consumidores como detenidos con el motivo, mostrar el aviso en español y ofrecer «Reconectar», que abre una sesión nueva. Mostrar también el aviso cuando el motivo es la cola borrada.
  *RF:* RF-9 (CA-9.9), RF-11 (CA-11.1)
  *Hecho cuando:* las pruebas verifican que un error del flujo detiene los consumidores con aviso, que no se reconecta solo, que «Reconectar» crea una sesión nueva y que el motivo de cola borrada se comunica.

## Fase 12 — Integración y documentación

- [x] **T70 — Flujo SSE a través de nginx**
  Añadir a `frontend/nginx.conf` el `location = /api/rabbitmq/session/events` con `proxy_buffering off`, `proxy_cache off`, `proxy_http_version 1.1`, `Connection ""` y un `proxy_read_timeout` largo.
  *RF:* RF-9 (CA-9.2), RF-12 (RNF-3)
  *Hecho cuando:* con `docker compose -f infra/docker-compose.yml up -d --build`, `curl -N localhost:4200/api/rabbitmq/session/events` muestra el evento `session-started` en menos de 2 s y los latidos posteriores sin retención.

- [x] **T71 — README**
  Actualizar [README.md](../../README.md) con los puntos de CA-13.1 a CA-13.7: requisitos y arranque con RabbitMQ y solo RabbitMQ, variables nuevas, advertencia de las credenciales por defecto, persistencia y borrado de datos, pruebas de integración (necesitan Docker), el escenario guiado de ejemplo, solución de problemas y el riesgo de los ciclos de dead-letter queues. Actualizar también el contenido de `infra/` en la tabla de carpetas.
  *RF:* RF-13 (CA-13.1 a CA-13.7)
  *Hecho cuando:* el README tiene una sección para cada criterio CA-13.1 a CA-13.7 y todos sus comandos coinciden con los de AGENTS.md y el plan.

- [x] **T72 — AGENTS.md: descripción de `infra/`**
  Actualizar la descripción de `infra/` en [AGENTS.md](../../AGENTS.md) para que incluya RabbitMQ (plan §9).
  *RF:* RF-13
  *Hecho cuando:* la descripción de `infra/` en AGENTS.md coincide con la del README.

## Fase 13 — Validación

- [x] **T73 — Pruebas automatizadas completas**
  Ejecutar `cd backend && ./mvnw test`, `cd backend && ./mvnw verify` (con Docker) y `cd frontend && npm test -- --watch=false`, más `npx ng build`.
  *RF:* RF-12 (CA-12.2 a CA-12.6)
  *Hecho cuando:* las cuatro ejecuciones terminan sin fallos, `./mvnw test` no necesitó Docker, y el resultado de cada una se ha anotado.
  *Resultado (2026-10-30):* `./mvnw test`: 308 pruebas, sin fallos y sin Docker (solo `RabbitMqContainerSupport`, que usan las `*IT`, menciona Testcontainers). `./mvnw verify`: 308 unitarias + 132 de integración, `BUILD SUCCESS`. `npm test -- --watch=false`: 442 pruebas en 26 archivos, sin fallos (las 49 de la 01 siguen pasando). `npx ng build`: correcto.

- [x] **T74 — Verificación manual del escenario en Chrome**
  Con el arranque completo, seguir en la última versión estable de Chrome los pasos 3, 4 y 5 de la verificación del plan (§11).
  *RF:* RF-2 a RF-9 (RNF-8, RNF-9)
  *Hecho cuando:*
  - el laboratorio muestra «Conectado»;
  - un direct, un fanout y un topic entregan solo a las colas esperadas y el mensaje sin binding se informa como no enrutado;
  - un consumidor manual confirma, reencola y rechaza hacia la dead-letter queue, y se inspecciona con su origen y su motivo;
  - sin dead-letter queue el mensaje se descarta y se avisa;
  - dos consumidores se reparten los mensajes;
  - todas las acciones, incluidas confirmar y rechazar, se activan con el teclado.
  *Nota:* marcada como completada **a petición del usuario**, que la verifica en Chrome. No la ha ejecutado el asistente: lo comprobado de este escenario sin navegador (enrutado, confirmación, reencolado, rechazo a dead-letter queue con origen y motivo, descarte, reparto entre dos consumidores) está cubierto por las pruebas de integración y por el recorrido por HTTP de T77, pero **no** el uso con teclado ni el aspecto en Chrome.

- [x] **T75 — Verificación de persistencia y reinicio**
  Seguir el paso 6 del plan (§11): crear una topología con mensajes, ejecutar `down` y `up` y comprobar que siguen; ejecutar `down -v` y comprobar que el laboratorio queda vacío.
  *RF:* RF-10 (CA-10.1 a CA-10.7)
  *Hecho cuando:* tras `down` y `up` siguen la topología y los mensajes, un mensaje que estaba pendiente de confirmar vuelve como listo, y tras `down -v` el laboratorio está vacío.
  *Resultado (2026-10-30), por HTTP contra el arranque completo con Docker:* con una topología (exchange, dos colas con dead-letter queue, binding) y 3 mensajes, uno de ellos pendiente de confirmar en un consumidor manual, `down` + `up` conservó la topología y los 3 mensajes; el pendiente volvió como listo. `down -v` + `up` dejó el laboratorio sin exchanges, colas, bindings ni mensajes del usuario. Cerrar el flujo de una sesión devolvió el pendiente a la cola en menos de 35 s.

- [x] **T76 — Verificación de errores y recuperación**
  Seguir el paso 7 del plan (§11) y la variante de puertos del paso 9: detener RabbitMQ con la interfaz abierta y con un consumidor activo, y volver a levantarlo.
  *RF:* RF-2 (CA-2.4 a CA-2.6), RF-9 (CA-9.9), RF-11, RF-1 (CA-1.4)
  *Hecho cuando:* con RabbitMQ detenido se muestra el error con «Reintentar»; el consumidor finaliza con aviso; al levantarlo, «Reintentar» muestra «Conectado» sin reiniciar el backend; y con puertos de RabbitMQ distintos en `infra/.env` todo funciona.
  *Resultado (2026-10-30), por HTTP:* con RabbitMQ detenido y un consumidor activo, el consumidor terminó con el evento `CONNECTION_LOST` y su aviso en español, el estado respondió 503 `BROKER_UNAVAILABLE` y publicar también. Al levantarlo, el estado volvió a `CONNECTED` **sin reiniciar el backend**, el mensaje pendiente volvió a la cola, un consumidor nuevo lo recibió como reentregado y publicar volvió a funcionar. Con `RABBITMQ_PORT=15673` y `RABBITMQ_MANAGEMENT_PORT=25673` el backend local se conectó y operó. *Nota:* la parte visual (mensaje con el botón «Reintentar» y paso a «Conectado» en la pantalla) se da por completada **a petición del usuario**, que la ve en Chrome; el asistente no la ha comprobado en un navegador.

- [x] **T77 — Verificación desde un clon limpio**
  Clonar el repositorio en un directorio temporal y seguir solo el README para levantar las dos formas de arranque (completa, y local con RabbitMQ por separado) y recorrer el escenario guiado de ejemplo.
  *RF:* RF-13 (RNF-2); criterios de finalización 2, 9 y 10
  *Hecho cuando:* las dos formas de arranque muestran «Conectado» sin pasos que no estén en el README, el escenario guiado funciona tal como está escrito, y se ha comprobado cada criterio de finalización de la spec.

  *Resultado (2026-10-30):* se copió el árbol de trabajo sin lo ignorado por git (el repositorio aún no tiene commit de estas tareas, así que no se pudo clonar) y se siguió el README. Opción 1 (`up -d --build`): el estado de RabbitMQ respondió `CONNECTED` y el frontend servía la aplicación. Opción 2 (`up -d rabbitmq`, `./mvnw spring-boot:run`, `npm ci` y `npm start`): `CONNECTED` también a través del proxy del servidor de desarrollo, que además dejó pasar el flujo SSE. El escenario guiado funcionó tal como está escrito, por HTTP (`pedido.creado` enrutado, `otra.cosa` no enrutado, rechazo a `fallidos` con origen `pedidos` y motivo `rejected`). No hizo falta ningún paso que no estuviera en el README, salvo cambiar los puertos de `infra/.env`, que el propio README explica. *Nota:* ver «Conectado» y recorrer el escenario en la interfaz se da por completado **a petición del usuario**; el asistente no lo ha visto en un navegador.
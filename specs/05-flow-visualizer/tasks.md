# Tareas 05 — Visualizador de flujo

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Orden de dependencia. Una tarea solo se marca `[x]` cuando su «Hecho cuando» se ha ejecutado de verdad. Lo compartido (`flow` en el backend, `shared/flow` en el frontend) no puede depender de RabbitMQ (RNF-5, RNF-6).

## Fase 0 — Verificación técnica e infraestructura

- [x] **T01 — Comprobar el trazado en un RabbitMQ real**
  En un proyecto Compose aparte y con puertos alternativos, activar `rabbitmqctl trace_on` y registrar: formato real de los mensajes de `amq.rabbitmq.trace` (routing keys y cabeceras de publicación y de entrega), si `trace_on` repetido es inofensivo, qué dice `GET /api/vhosts` con el trazado activo e inactivo, si se pierde al reiniciar, y si una cola exclusiva puede enlazarse a `amq.rabbitmq.trace`. Anotar el resultado y ejemplos reales en `plan.md` (§ «Resultados de la verificación»).
  *RF:* RF-2, RF-3
  *Hecho cuando:* `plan.md` contiene los resultados y al menos un ejemplo real de publicación y de entrega; si algo contradice el plan, está corregido.

- [x] **T02 — El Compose activa el trazado**
  Añadir `infra/rabbitmq/enable-trace.sh` y usarlo en el servicio `rabbitmq` (arranque del servidor + `trace_on` repetido en segundo plano), sin cambiar puertos ni volumen.
  *RF:* RF-2 (CA-2.1)
  *Hecho cuando:* en un proyecto aparte, tras `up` y tras reiniciar el contenedor, `GET /api/vhosts` indica el trazado activo; los datos del volumen se conservan; y se ha comprobado que el contenedor del usuario puede recrearse con `up -d` sin perder sus colas.

## Fase 1 — Núcleo del backend (genérico)

- [x] **T03 — Contrato del flujo**
  Añadir a `backend/api/api.yml` los esquemas genéricos `Flow*` y las operaciones `status`, `topology`, `events` y `stream` bajo `/api/rabbitmq/flow`.
  *RF:* RF-3, RF-4, RF-5, RF-6
  *Hecho cuando:* `./mvnw compile` genera la interfaz y los modelos sin errores y el contrato no menciona exchange ni cola en los esquemas compartidos (solo en descripciones).

- [x] **T04 — Modelo y buffer de eventos**
  Crear en `com.messaginglab.flow` el modelo (`FlowEvent`, nodos, aristas, topología, tick, estado) y `FlowEventBuffer` (anillo de 1000, seguro entre hilos), con pruebas.
  *RF:* RF-3, RF-5 (CA-5.2); RNF-1, RNF-5
  *Hecho cuando:* las pruebas verifican orden, capacidad de 1000, descarte de lo más antiguo y acceso concurrente; `CoreIndependenceTest` incluye el paquete `flow` y pasa.

- [x] **T05 — Ritmos, series y muestreo**
  Crear `EventSampler` (máximo 50 eventos/s al navegador, con omitidos contados), con pruebas. Los ritmos por nodo y la serie de 2 minutos los calcula `FlowService` (T07), sin clase propia.
  *RF:* RF-3 (CA-3.6), RF-6; RNF-1
  *Hecho cuando:* las pruebas verifican que con 500 eventos/s pasan 50 y se cuentan 450 omitidos, y que se empieza una ventana nueva cada segundo. (Los ritmos exactos y la serie de 2 minutos se verifican en T07.)

- ~~**T06 — Resolución de rutas**~~ *Suprimida:* el trazado de RabbitMQ ya informa de las colas a las que enruta cada mensaje (`routed_queues`, ver plan §7), así que no se calcula a partir de los bindings.

- [x] **T07 — `FlowService` y puertos**
  Definir los puertos `FlowSource` y `FlowTopologyProvider` y crear `FlowService` (cuenta de pantallas, arranque y parada de la fuente, buffer, ticks, reparto a suscriptores acotados), con una fuente falsa en las pruebas.
  *RF:* RF-3, RF-5, RF-7 (CA-7.3); RNF-1
  *Hecho cuando:* las pruebas verifican que la fuente solo se arranca con una pantalla y se para con la última, que un suscriptor lento no acumula memoria, el historial al suscribirse y los ticks de cada segundo.

## Fase 2 — Adaptador y API de RabbitMQ

- [x] **T08 — Traducción de los mensajes del trazado**
  Crear en `flow.rabbitmq` el traductor de mensajes de `amq.rabbitmq.trace` a `FlowEvent` (publicación y entrega, cuerpo truncado a 4 KB, descarte de las entregas de la cola propia), con los ejemplos reales de T01.
  *RF:* RF-3 (CA-3.1 a CA-3.3), RF-2 (CA-2.4)
  *Hecho cuando:* las pruebas verifican ambos tipos de evento con mensajes reales, el truncado, las reentregas y el descarte del tráfico propio.

- [x] **T09 — Fuente de trazado y estado**
  Crear `TraceFlowSource`: cola propia exclusiva y acotada enlazada al trazado, consulta periódica del estado del vhost (`UNAVAILABLE`/`DISABLED`/`ACTIVE`) con el comando en el `hint`, y suscripción automática al activarse.
  *RF:* RF-1 (CA-1.3), RF-2 (CA-2.2, CA-2.3)
  *Hecho cuando:* las pruebas con fakes verifican las tres transiciones de estado y que no se mantiene la suscripción sin pantallas.

- [x] **T10 — Topología e identidades**
  Crear el proveedor de topología de RabbitMQ (exchanges, colas, bindings, consumidores y etiquetas de publicadores y consumidores, `ready` por cola) con refresco periódico y bajo demanda.
  *RF:* RF-4 (CA-4.1, CA-4.3, CA-4.5)
  *Hecho cuando:* las pruebas verifican nodos y aristas a partir de la topología falsa, las etiquetas de conexiones y consumidores, y el refresco al aparecer un nodo desconocido.

- [x] **T11 — Controlador**
  Crear `RabbitMqFlowController` (estado, topología, historial y SSE con lotes, ticks y latidos) y sus pruebas.
  *RF:* RF-3 (CA-3.5), RF-7
  *Hecho cuando:* las pruebas verifican respuestas y errores en español, que el SSE emite lotes, ticks y cambios de estado, y que cerrar la conexión libera la suscripción.

- [x] **T12 — Pruebas de integración con el trazado real**
  Escribir `FlowIT` con RabbitMQ real y trazado activado.
  *RF:* RF-2, RF-3, RF-4
  *Hecho cuando:* `./mvnw verify` pasa con pruebas que verifican: publicación y entrega a un consumidor, publicación sin enrutar, el tráfico del propio visualizador ausente, entrega a un consumidor simulado, estado `DISABLED` con el trazado apagado y recuperación al reactivarlo.

## Fase 3 — Frontend compartido (`shared/flow`)

- [x] **T13 — Tipos, fuente y funciones puras**
  Crear los tipos, la interfaz `FlowSource` con su token, `layoutFlow`, `matchesFilter` y `routeOf`, con pruebas, y una comprobación de que `shared/flow` no importa de `features/`.
  *RF:* RF-4 (CA-4.1, CA-4.8); RNF-6
  *Hecho cuando:* las pruebas verifican el diseño por columnas, los filtros con comodines de topic, los caminos de publicación y de entrega y la ausencia de dependencias de `features/`.

- [x] **T14 — Almacén de datos y reconexión**
  Crear `FlowStore`: carga estado, topología e historial, consume el flujo, conserva lo mostrado si se corta y reintenta solo.
  *RF:* RF-7 (CA-7.1, CA-7.2)
  *Hecho cuando:* las pruebas con una fuente falsa verifican la carga inicial, el corte y la recuperación sin perder lo mostrado, los estados vacío, error y no disponible, y que se libera la conexión al destruirse.

- [x] **T15 — Registro de eventos**
  Crear `FlowLog` con pausa, reanudar, limpiar y detalle del evento.
  *RF:* RF-5, RF-3 (CA-3.2, CA-3.3)
  *Hecho cuando:* las pruebas verifican columnas, orden, pausa y reanudación sin perder eventos, limpiar, el detalle con cuerpo escapado (un cuerpo con HTML se ve como texto) y el aviso de eventos omitidos.

- [x] **T16 — Diagrama**
  Crear `FlowDiagram` (SVG con nodos, aristas, ritmo y mensajes listos).
  *RF:* RF-4 (CA-4.1, CA-4.3, CA-4.5)
  *Hecho cuando:* las pruebas verifican que dibuja la topología recibida, actualiza ritmos y `ready`, y que cambia al llegar una topología nueva.

- [x] **T17 — Animador**
  Crear `FlowAnimator`: partículas por camino, pausa, cámara lenta, tope de 50/s con omitidos, forma distinta para los no enrutados y modo sin animación con movimiento reducido.
  *RF:* RF-4 (CA-4.2, CA-4.4, CA-4.6, CA-4.7); RNF-1, RNF-4
  *Hecho cuando:* las pruebas con reloj simulado verifican posiciones por tiempo, pausa, factor de velocidad, tope y omitidos, el distintivo de no enrutados y que con movimiento reducido no se crean partículas.

- [x] **T18 — Gráficas**
  Crear `FlowCharts` (mensajes por segundo y mensajes listos, 2 minutos, SVG propio) con tabla accesible.
  *RF:* RF-6
  *Hecho cuando:* las pruebas verifican las series, las unidades de los ejes, la leyenda, la selección de series, la tabla equivalente y que no hay valores fijos de color que fallen en tema oscuro.

- [x] **T19 — `FlowView` y reutilización demostrada**
  Crear `FlowView` (aviso de estado, controles, filtro, diagrama, registro y gráficas) y una prueba que lo monta con una fuente falsa **no RabbitMQ**.
  *RF:* RF-1 (CA-1.3), RF-4 (CA-4.6, CA-4.8), RF-7; RNF-6
  *Hecho cuando:* las pruebas verifican los tres estados de la fuente (con el comando de activación), pausa, cámara lenta, filtro y limpiar, y que funciona con una fuente cuyos nodos tienen otros nombres.

## Fase 4 — Integración en el Laboratorio RabbitMQ

- [x] **T20 — Fuente de RabbitMQ y pestaña «Flujo»**
  Crear `RabbitMqFlowSource` (HTTP + SSE) y `RabbitMqFlowPage`; añadir la ruta diferida `flow` y la pestaña con posición recordada; quitar «Visualizador de flujo» del menú y ajustar las pruebas del menú y del laboratorio.
  *RF:* RF-1, RF-7 (CA-7.4)
  *Hecho cuando:* las pruebas verifican la fuente contra `HttpTestingController` y el `EventSource` falso, la pestaña y la ruta, y que el menú ya no tiene la entrada; `npm test -- --watch=false` y `npx ng build` pasan sin superar el presupuesto de tamaño inicial.

- [x] **T21 — README**
  Documentar el visualizador, qué es el trazado, su coste y cómo activarlo y desactivarlo a mano, y cómo reutilizar `shared/flow` para otro laboratorio.
  *RF:* CA-2.5
  *Hecho cuando:* el README lo cubre y sus comandos coinciden con los del Compose.

## Fase 5 — Validación

- [x] **T21b — Dibujar solo el flujo activo** *(añadida a petición del usuario)*
  `FlowStore` registra la actividad de cada nodo y `FlowView` pasa al diagrama solo los activos en los últimos 10 s, con un aviso si no hay ninguno.
  *RF:* RF-4 (CA-4.1b)
  *Hecho cuando:* las pruebas verifican que no se dibuja nada sin mensajes, que aparece lo que atraviesa un mensaje (productor, enrutador, destinos y, en entregas, consumidor), que desaparece a los 10 s sin actividad, que tener mensajes listos no cuenta y que los ticks antiguos reenviados al conectar se ignoran.

- [x] **T21c — Estado de RabbitMQ y del trazado en la pantalla «Estado»** *(añadida a petición del usuario)*
  Añadir a la pantalla «Estado» un bloque que consulta `/api/rabbitmq/status` y `/api/rabbitmq/flow/status` y muestra si RabbitMQ está levantado y si el trazado está activo.
  *RF:* RF-2 (CA-2.6)
  *Hecho cuando:* las pruebas verifican los estados «comprobando», levantado con versión y trazado activo, trazado desactivado con su aviso, RabbitMQ caído sin afirmar nada del trazado, el error de la propia comprobación y el botón «Volver a comprobar». `npm test -- --watch=false` y `npx ng build` pasan.

- [x] **T22 — Verificación completa**
  Ejecutar `./mvnw test`, `./mvnw verify`, `npm test -- --watch=false` y `npx ng build`; comprobar por HTTP, en un proyecto Compose aparte, los criterios de finalización 2, 3, 4 y 5 (incluido el generador a 100 mensajes por segundo), y el reinicio de RabbitMQ.
  *RF:* todos
  *Hecho cuando:* todo pasa con las pruebas de las especificaciones 01 a 04 incluidas y los resultados están anotados.

- [x] **T23 — Prueba en Chrome**
  El usuario comprueba el diagrama animado, la cámara lenta, el filtro, las gráficas y el comportamiento con el generador en marcha.
  *RF:* RF-4, RF-6; RNF-4
  *Hecho cuando:* el usuario confirma los criterios de finalización 3 a 7.

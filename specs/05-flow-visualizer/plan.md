# Plan 05 — Visualizador de flujo

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Referencias:** [spec.md](spec.md)

## 1. Principio de diseño: un visualizador genérico, dos laboratorios

El visualizador se construye **una vez** y cada laboratorio le aporta solo su fuente de datos (RNF-6):

```
Backend                                          Frontend
┌───────────────────────────────┐               ┌─────────────────────────────────┐
│ flow (núcleo, sin RabbitMQ)   │               │ shared/flow (sin RabbitMQ)      │
│  FlowEvent, FlowTopology,     │   contrato    │  FlowView ← diagrama, registro, │
│  FlowEventBuffer, FlowRates,  │  genérico     │  gráficas, controles, filtro    │
│  FlowService   │◄────────────► │  FlowSource (token/interfaz)    │
│  puerto FlowSource            │  /flow/*      └───────────▲─────────────────────┘
└──────────────▲────────────────┘                           │ implementa
               │ implementa                      features/rabbitmq/flow  (hoy)
   flow.rabbitmq (hoy): trazado                  features/kafka/flow     (futuro)
   flow.kafka    (futuro)
```

- **Lo compartido no conoce a ningún sistema.** El núcleo trabaja con *nodos* genéricos (productor, enrutador, destino, consumidor) y *eventos* genéricos (publicación, entrega). En RabbitMQ, enrutador = exchange y destino = cola; en Kafka serán topic y partición, sin tocar el núcleo.
- **Lo propio de cada sistema** es un adaptador de backend (fuente de eventos y de topología), un controlador con las rutas `/api/<sistema>/flow/*` que usan los **mismos esquemas** del contrato, y una pestaña «Flujo» con su `FlowSource`.
- Una comprobación automática (`CoreIndependenceTest`, ya existente) se amplía al paquete `flow`; y en el frontend se prueba que `shared/flow` no importa nada de `features/`.

## 2. Contrato (`backend/api/api.yml`)

Esquemas genéricos reutilizables (`Flow*`) y, bajo `/api/rabbitmq/flow`, las operaciones (Kafka repetirá las mismas bajo `/api/kafka/flow`):

| Operación | Descripción |
|---|---|
| `GET /status` | `FlowSourceStatus`: `UNAVAILABLE`, `DISABLED` (trazado desactivado) o `ACTIVE`; con un `hint` en español (el comando para activarlo). |
| `GET /topology` | `FlowTopology`: `nodes` (`id`, `kind`: PRODUCER/ROUTER/DESTINATION/CONSUMER, `label`, `detail`) y `edges` (`from`, `to`, `label`). |
| `GET /events?limit=` | Últimos eventos (hasta 1000) para no abrir la pantalla vacía. |
| `GET /stream` (SSE) | Eventos `flow-event` (≤ 50 por segundo), `tick` (cada segundo: ritmos publicados y entregados por nodo, `ready` por destino y `omitted`; al conectar se reenvían los de los últimos 2 minutos), `status` y `topology` (al conectar y cuando cambian). |

`FlowEvent`: `id`, `time`, `type` (PUBLISH/DELIVER), `router`, `destination`, `key`, `sizeBytes`, `redelivered`, `routedTo[]` (colas a las que RabbitMQ la enrutó, según el propio trazado), `unrouted`, `publisher`, `consumer`, `body` (≤ 4 KB), `properties`, `headers`. Los campos específicos de un sistema viajan en `properties`/`headers`, no como campos nuevos.

## 3. Backend

### 3.1 Núcleo `com.messaginglab.flow` (sin RabbitMQ, Spring ni `api`)
- **Modelo:** `FlowEvent`, `FlowNode`, `FlowEdge`, `FlowTopology`, `FlowTick`, `FlowSourceStatus`.
- **`FlowEventBuffer`:** anillo de 1000 eventos, seguro entre hilos (CA-5.2, RNF-1).
- **`EventSampler`:** deja pasar como máximo 50 eventos/s al flujo del navegador y cuenta los omitidos; los contadores siguen exactos (CA-3.6).
- Los ritmos por nodo y la serie de 2 minutos no necesitan una clase propia: `FlowService` cuenta los eventos de cada segundo, los convierte en un `FlowTick` y conserva los últimos 120 (CA-6.1).
- **Puertos:** `FlowSource` (arranca/para una escucha de eventos y informa de su estado) y `FlowTopologyProvider` (instantánea de nodos y aristas, con `ready` por destino).
- **`FlowService`:** cuenta las pantallas abiertas y **solo arranca la fuente con al menos una** (CA-7.3); rellena el buffer, calcula los ticks y reparte a los suscriptores (SSE) con colas acotadas por suscriptor; ante un suscriptor lento descarta, nunca acumula (RNF-1).

### 3.2 Adaptador de RabbitMQ (en `com.messaginglab.gateway.rabbitmq`, junto a los demás adaptadores, que son los que pueden usar el cliente de gestión y AMQP)
- **Trazado (firehose):** `trace_on` hace que RabbitMQ copie cada publicación y cada entrega al exchange `amq.rabbitmq.trace` (topic) del vhost, con routing keys `publish.<exchange>` y `deliver.<cola>`. El formato real está en «Resultados de la verificación».
- **`TraceFlowSource`:** declara una cola **exclusiva, de autoborrado y acotada** (`x-max-length`, `drop-head`) con nombre propio `ml.flow.trace.<uuid>`, enlazada a `amq.rabbitmq.trace` con `#`, en autoconfirmación. Traduce cada mensaje a `FlowEvent`, trunca el cuerpo a 4 KB y **descarta las entregas de su propia cola** (CA-2.4).
- **Estado (CA-1.3, CA-2.2, CA-2.3):** la API de gestión (`GET /api/vhosts`) informa de si el vhost tiene el trazado activo. Se consulta cada pocos segundos mientras hay pantallas abiertas: `UNAVAILABLE` si RabbitMQ no responde, `DISABLED` si no hay trazado (con el comando en `hint`), `ACTIVE` si lo hay; al pasar a `ACTIVE` la fuente se suscribe sola.
- **Topología:** `BrokerTopology` (exchanges, colas, bindings) más la API de gestión para conexiones y consumidores. Las colas se leen como máximo cada segundo y el resto cada 5 s (CA-4.5). Los ids de publicadores y consumidores se construyen solo con lo que trae el evento (`producer:<conexión>`, `consumer:<conexión>|<canal>|<cola>`), y el nombre legible (el que puso el cliente a su conexión, más la etiqueta del consumidor) se resuelve al dibujar, porque RabbitMQ tarda unos segundos en mostrar las conexiones nuevas en su API de gestión.
- Toda la comunicación con RabbitMQ pasa por los adaptadores existentes (`ManagementApiClient`, conexión AMQP); no se añaden dependencias.

### 3.3 Controlador
`RabbitMqFlowController` implementa las operaciones del contrato y traduce entre contrato y núcleo. El SSE usa `SseEmitter` como las sesiones de consumidores, con latidos.

### 3.4 Infraestructura
`trace_on` no se puede fijar por configuración ni por la API HTTP, y se pierde al reiniciar RabbitMQ. El servicio `rabbitmq` del Compose se arranca con un pequeño guion (`infra/rabbitmq/enable-trace.sh`, montado en el contenedor) que lanza el servidor y, en segundo plano, ejecuta `rabbitmqctl await_startup` y repite `rabbitmqctl trace_on` periódicamente (idempotente), de modo que se reactiva tras cualquier reinicio (CA-2.1). Recrear el contenedor conserva el volumen y los datos; la conexión AMQP del backend y los consumidores se recuperan solos como en la especificación 02.

## 4. Frontend

### 4.1 `shared/flow` (genérico)
- **Tipos y `FlowSource`:** interfaz con `status()`, `topology()`, `recent()`, y un flujo `stream()` (eventos, ticks, estado, topología); inyectada con un token. La reconexión automática y la conservación de lo mostrado (CA-7.2) viven en un `FlowStore` compartido, no en cada fuente.
- **Funciones puras (fáciles de probar):** `layoutFlow(topology)` (columnas productores → enrutadores → destinos → consumidores), `matchesFilter(event, filter)` (exchange, cola o routing key con comodines, CA-4.8) y `routeOf(event)` (camino por nodos).
- **`FlowDiagram`:** SVG con nodos, aristas, ritmo y `ready` por nodo; recibe las partículas a pintar.
- **Solo el flujo activo (CA-4.1b):** `FlowStore` anota cuándo pasó un mensaje por cada nodo (por los eventos en vivo y por los ritmos de los ticks recientes, ignorando los antiguos que el servidor reenvía al conectar) y expone los activos en los últimos 10 s; `FlowView` filtra la topología con ellos antes de dárselos al diagrama. La comparación con el reloj del navegador supone que navegador y backend tienen la hora parecida (en el laboratorio están en la misma máquina).
- **`FlowAnimator`:** reloj propio (inyectable en pruebas) que convierte eventos en partículas que recorren su camino; implementa pausa, cámara lenta (factor de tiempo), el límite de 50/s con «se omiten N eventos por segundo», el color y la forma distintos de los no enrutados (no depende solo del color) y el modo sin animación con `prefers-reduced-motion` (CA-4.2 a CA-4.7).
- **`FlowLog`:** registro con pausa, limpiar, detalle del evento (cuerpo escapado por interpolación de Angular, nunca HTML) (CA-3.3, CA-5.x).
- **`FlowCharts`:** gráficas de líneas **propias en SVG** (sin librería de gráficos, para no añadir dependencias) de mensajes por segundo y de mensajes listos, con título, ejes con unidades, leyenda, tabla accesible equivalente y colores que funcionan en tema claro y oscuro.
- **`FlowView`:** compone todo con el aviso de estado, los controles y el filtro. Es lo que cada laboratorio incluye en su pestaña.

### 4.2 `features/rabbitmq/flow`
- `RabbitMqFlowSource` (HTTP + SSE con el mismo patrón de `EventSource` inyectable que las sesiones de consumidores) y `RabbitMqFlowPage`, que solo pone `FlowView` con esa fuente y el vocabulario de RabbitMQ (exchange, cola).
- Ruta `flow` cargada de forma diferida y pestaña «Flujo» en el laboratorio, con la posición recordada (CA-7.4).
- Se elimina «Visualizador de flujo» de `nav-sections.ts` y se ajustan las pruebas del menú (CA-1.2).

### 4.3 Para el Laboratorio Kafka (no se hace aquí)
Basta con `KafkaFlowSource`, una pestaña «Flujo» con `FlowView`, un adaptador `flow.kafka` y un controlador bajo `/api/kafka/flow`. No se modifica `shared/flow` ni el núcleo del backend. Para dejarlo demostrado, una prueba del frontend monta `FlowView` con una **fuente falsa no RabbitMQ** (otros nombres y tipos de nodo).

## 5. Estrategia de pruebas
- **Núcleo del backend:** pruebas unitarias con relojes y fuentes falsas (`ManualScheduler`, `MutableClock`), incluida la independencia de RabbitMQ.
- **Adaptador:** traducción de mensajes del trazado a eventos con casos reales capturados en T01; descarte del tráfico propio; truncado y escapado.
- **Integración (`*IT`, Testcontainers):** con el trazado activado en el contenedor, publicar, enrutar, no enrutar, entregar a un consumidor y a uno simulado; el tráfico del propio visualizador no aparece; trazado apagado → `DISABLED`; reinicio de RabbitMQ → recuperación.
- **Frontend:** pruebas de funciones puras, de cada componente con tiempo simulado, del almacén (reconexión) y de la integración con una fuente falsa. El rendimiento con 500 eventos/s se comprueba en el backend por HTTP y en Chrome (T20).

## 6. Riesgos
- **Coste del trazado:** duplica cada mensaje dentro de RabbitMQ. Es aceptable en un laboratorio y se advierte en el README (CA-2.5); se puede desactivar con `rabbitmqctl trace_off`.
- **Formato del trazado:** se verifica con RabbitMQ 4.4 real antes de escribir el adaptador (T01).
- **Rendimiento del navegador:** límite de 50 eventos/s animados, partículas con tope y animación pausable.
- **Reutilización teórica:** se mitiga con la prueba de la fuente falsa no RabbitMQ y con la comprobación de importaciones.

## 7. Resultados de la verificación (T01 y T02)

Comprobado con RabbitMQ 4.4 real, cliente AMQP 5.30.0 y la API de gestión.

- **Trazado:** `rabbitmqctl trace_on` es idempotente. `GET /api/vhosts` expone `tracing: true|false`, que usaremos para el estado. **Se pierde al reiniciar el nodo**, de ahí el bucle del Compose (T02). El exchange `amq.rabbitmq.trace` es `topic` e `internal`.
- **Colas:** RabbitMQ 4.4 **rechaza las colas transitorias no exclusivas** (`transient_nonexcl_queues` está obsoleta). La cola del visualizador debe ser **exclusiva** (se declara por AMQP; la API HTTP no puede crearlas). Una cola exclusiva, de autoborrado y con `x-max-length` + `drop-head` se enlaza sin problema a `amq.rabbitmq.trace` con `#`.
- **Publicación** (routing key `publish.<exchange>`), cabeceras: `exchange_name`, `routing_keys[]`, **`routed_queues[]`** (las colas a las que RabbitMQ ha enrutado el mensaje; vacío = sin enrutar), `properties` (con sus `headers`), `connection`, `channel`, `user`, `vhost`, `node`.
  Ejemplo real: `publish.ex1`, cuerpo `hola` → `{exchange_name: ex1, routing_keys: [a.x], routed_queues: [q1], connection: "172.17.0.1:58842 -> 172.17.0.2:5672", channel: 1, user: guest, properties: {}}`. Una publicación sin destino llega con `routed_queues: []`.
- **Entrega** (routing key `deliver.<cola>`), mismas cabeceras más `redelivered` (0/1) y sin `routed_queues`. Ejemplo: `deliver.q1` con `connection: "172.17.0.1:58858 -> 172.17.0.2:5672"`.
- **Consecuencia para el diseño:** como el evento ya trae las colas destino, **no hace falta `RouteResolver`** (T06 suprimida) y no se duplica la lógica de enrutado.
- **Identidades:** la cabecera `connection` coincide con el `name` de `GET /api/connections`, cuyo `client_properties.connection_name` es el nombre que pone el cliente. Para los consumidores, `GET /api/consumers/%2F` da `consumer_tag`, `queue` y `channel_details.connection_name`, que se cruza con la `connection` y el `channel` de la entrega.
- **Compose:** `infra/rabbitmq/enable-trace.sh` arranca RabbitMQ con `exec` (las señales llegan al servidor y el contenedor sigue siendo «healthy») y repite `trace_on` cada 15 s con `gosu rabbitmq`. Verificado en un proyecto aparte: trazado activo al arrancar, tras `restart` y tras `--force-recreate`, con el volumen y sus datos intactos (la cola creada antes se conserva).
- **Retraso de la API de gestión (descubierto en `FlowIT`):** una conexión nueva tarda hasta unos segundos en aparecer en `GET /api/connections`. Por eso los ids de los nodos no dependen de ella y el nombre legible aparece cuando la API lo conoce.

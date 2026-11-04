# Plan 08 — Base del Laboratorio Kafka

- **Estado:** Aprobado
- **Fecha:** 2026-11-02
- **Referencias:** [spec.md](spec.md)

## 1. Arquitectura

```
navegador ─► frontend (nginx) ─/api/kafka/*─► backend ─► kafka (KRaft, un nodo)
                                                 └─ control ─► docker-proxy ─► contenedor kafka
```

- **Paquetes nuevos en el backend, separados de RabbitMQ:**
  - `kafka`: núcleo con los modelos, la validación, los casos de uso y los puertos, sin dependencias del cliente de Kafka ni de Spring.
  - `gateway.kafka`: adaptador con `kafka-clients`.
  - `controller`: `KafkaController` implementa la interfaz generada del contrato.
- **Los puertos de RabbitMQ no se reutilizan:** `BrokerTopology` y `BrokerPublisher` hablan de exchanges, colas y bindings, y forzarlos a Kafka los empeoraría. `CoreIndependenceTest` incluirá el núcleo nuevo.
- **Frontend:** `features/kafka/`, con el mismo armazón de laboratorio y los componentes compartidos ya existentes.

## 2. Broker e infraestructura

- **Servicio `kafka`** con la imagen `apache/kafka`, versión fijada, y volumen `kafka-data`.
- **Dos listeners:** `INTERNAL://kafka:29092` para el backend dentro de Docker, y `EXTERNAL://localhost:${KAFKA_PORT:-9092}`, publicado solo en `127.0.0.1`, para el arranque local.
- **Un nodo:** hace de broker y de controller, con factor de replicación 1 en los topics internos.
- **Memoria:** heap acotado con `KAFKA_HEAP_OPTS` y `mem_limit`, con valores medidos en T01.
- **Creación automática de topics desactivada**, para que producir en un topic que no existe sea un error explicado y no cree uno sin querer.
- **`KAFKA_REPLICAS`:** `deploy.replicas`, como con Ollama.
- **Backend:** `KAFKA_BOOTSTRAP_SERVERS`, con `localhost:9092` por defecto y `kafka:29092` en el Compose. En local usa `KAFKA_PORT` de `infra/.env`.

## 3. Contrato (`backend/api/api.yml`, etiqueta `Kafka`)

| Operación | Para qué |
|---|---|
| `GET /api/kafka/status` | Conexión y versión |
| `GET /api/kafka/topics` | Topics con particiones y mensajes |
| `POST /api/kafka/topics` | Crear un topic |
| `GET /api/kafka/topics/{name}` | Detalle por partición |
| `DELETE /api/kafka/topics/{name}` | Eliminar un topic |
| `POST /api/kafka/topics/{name}/messages` | Producir; devuelve partición y offset |
| `GET /api/kafka/topics/{name}/partitions/{partition}/messages` | Leer el log, con `from`, `offset` y `limit` |

**Errores:** se reutilizan `VALIDATION`, `NOT_FOUND`, `CONFLICT` y `TIMEOUT`, y `BROKER_UNAVAILABLE` con un mensaje propio de Kafka.

**`GET /api/control/services` y sus acciones:** se añade `KAFKA` y `/services/kafka/actions`.

## 4. Adaptador

- **`Admin`:** topics, particiones y offsets (`listTopics`, `createTopics`, `describeTopics`, `listOffsets`, `deleteTopics`, `describeCluster`).
  - La versión se lee de `describeFeatures` o del propio cliente; se decide en T01 según lo que informe el broker.
- **`KafkaProducer`:** uno compartido, con `acks=all`, `max.block.ms` y `delivery.timeout.ms` cortos.
- **Lectura:** un `KafkaConsumer` nuevo por petición, con `assign()` y `seek()`, **sin `group.id` ni commits**. Así, leer no crea ningún consumer group (CA-5.4).
- **Fin de la lectura:** cuando llega al offset final conocido, al límite o a un tiempo máximo. Los valores se decodifican como UTF-8.
- **Mensajes en el log:** el número de mensajes se calcula con offset final menos inicial; se documenta como aproximado si hay marcadores de transacción, aunque el laboratorio no los genera.
- **Recuperación:** los clientes de Kafka reconectan solos. El estado de la conexión se cachea unos segundos para no bloquear las pantallas con el broker caído.

## 5. Control de servicios

- `ServiceControl.Service` añade `KAFKA` con el contenedor `kafka`, y su mensaje para cuando no existe (`KAFKA_REPLICAS=0`).
- La tarjeta reutiliza `ServiceControls`, que ya recibe el servicio. El tipo `ControlledServiceName` y los avisos de `STOP_WARNINGS` añaden Kafka.
- Las reglas del intermediario de Docker no cambian.

## 6. Pruebas

- **Núcleo:** con un Kafka en memoria que implementa los puertos.
- **Adaptador e integración:** `*IT` con Testcontainers: crear, producir con y sin clave, misma clave en la misma partición, leer sin retirar, sin consumer group, offset fuera de rango, eliminar, y broker caído y recuperación.
- **Frontend:** Vitest, igual que el Laboratorio RabbitMQ.

## 7. Riesgos

- **Memoria:** Kafka sobre JVM suma memoria a un stack que ya usa unos 4 GB con el asistente. Se mide en T01 y se documenta; `KAFKA_REPLICAS=0` lo evita.
- **Listeners:** un `advertised.listeners` mal configurado es el fallo clásico de Kafka en Docker. T01 prueba los dos arranques.
- **Tiempos con el broker caído:** los clientes de Kafka esperan mucho por defecto. Se fijan tiempos cortos y se prueban.
- **Versiones:** Spring Boot gestiona una versión de `kafka-clients`; se usa esa si es compatible con el broker, en lugar de fijar otra.

## 8. Resultados de la prueba técnica (T01)

Se rellena en T01.

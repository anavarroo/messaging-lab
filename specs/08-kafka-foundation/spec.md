# Especificación 08 — Base del Laboratorio Kafka

- **Estado:** Aprobada
- **Fecha:** 2026-11-02
- **Referencias:** [especificación 02](../02-rabbitmq-integration/spec.md) · [especificación 07](../07-decoupled-assistant/spec.md)

## 1. Contexto y objetivo

El laboratorio ya permite aprender RabbitMQ, pero «Laboratorio Kafka» sigue en el menú como «Disponible próximamente». Kafka funciona de otra manera: los mensajes no se retiran al leerlos, sino que quedan en un log dividido en particiones, y cada mensaje se identifica por su partición y su offset.

**Objetivo:** abrir el Laboratorio Kafka con lo esencial para ver esa diferencia: levantar un broker, crear topics, producir mensajes y leer el log.

Esta es la primera de cuatro especificaciones de Kafka:

| Especificación | Contenido |
|---|---|
| **08 (esta)** | Broker, topics, producción y lectura del log |
| 09 | Consumer groups, reparto de particiones, rebalanceos, commits y lag |
| 10 | Generador, visualizador de flujo y guía |
| 11 | Asistente de IA y comparación con RabbitMQ |

## 2. Usuarios

Los del Laboratorio RabbitMQ: quien quiere aprender o experimentar con un sistema de mensajería, sin experiencia previa en Kafka.

## 3. Requisitos

### RF-1 — Broker de Kafka en el Docker Compose
- **CA-1.1:** `docker compose -f infra/docker-compose.yml up -d --build` levantará también un broker de Kafka en modo KRaft, sin ZooKeeper y con un solo nodo.
- **CA-1.2:** Los topics y los mensajes se conservarán al parar y volver a levantar el broker, y se borrarán con `down -v`.
- **CA-1.3:** El puerto de Kafka solo será accesible desde la máquina local y se podrá cambiar con `KAFKA_PORT` en `infra/.env` (por defecto, `9092`).
- **CA-1.4:** Con `KAFKA_REPLICAS=0` no se levantará Kafka, y el resto de la aplicación funcionará igual.
- **CA-1.5:** El backend también funcionará en el arranque local por componentes, conectándose a `localhost:${KAFKA_PORT}`.

### RF-2 — Conexión y estado
- **CA-2.1:** «Laboratorio Kafka» dejará de estar deshabilitado en el menú y abrirá el laboratorio, con sus pestañas y el indicador de conexión (conectado con la versión, o no disponible).
- **CA-2.2:** «Estado» tendrá una tarjeta de Kafka que indicará si está levantado y su versión, con los botones Parar y Levantar de la especificación 07. Antes de parar se pedirá confirmación, explicando que el Laboratorio Kafka dejará de funcionar y que los mensajes guardados se conservan.
- **CA-2.3:** Si Kafka no está disponible, el Laboratorio Kafka lo dirá en español y se recuperará solo cuando vuelva, sin recargar la página ni reiniciar el backend.
- **CA-2.4:** RabbitMQ y Kafka serán independientes: que uno esté caído no afectará al otro, ni al arranque del backend.

### RF-3 — Topics
- **CA-3.1:** Se listarán los topics con su número de particiones y de mensajes. Los topics internos de Kafka (como `__consumer_offsets`) no se mostrarán.
- **CA-3.2:** Se podrá crear un topic indicando su nombre y su número de particiones (de 1 a 12; por defecto, 3). El nombre se validará con las reglas de Kafka y los errores se explicarán en español.
- **CA-3.3:** Crear un topic que ya existe se indicará con un error claro, sin cambiar el existente.
- **CA-3.4:** Al abrir un topic se verá, por partición, el offset inicial, el offset final y el número de mensajes.
- **CA-3.5:** Se podrá eliminar un topic, con confirmación que avise de que se perderán sus mensajes.

### RF-4 — Producir mensajes
- **CA-4.1:** Se podrá producir un mensaje eligiendo el topic, con clave opcional y un valor de texto (hasta 64 KB, como máximo).
- **CA-4.2:** Se podrá dejar que Kafka elija la partición o indicar una concreta.
- **CA-4.3:** Tras producir, se mostrará la partición y el offset asignados, con un enlace para verlo en el log.
- **CA-4.4:** La pantalla explicará que los mensajes con la misma clave van a la misma partición, y que sin clave se reparten entre las particiones.

### RF-5 — Leer el log
- **CA-5.1:** Se podrán leer los mensajes de una partición desde un offset, como máximo 100 cada vez, viendo su offset, clave, valor y fecha.
- **CA-5.2:** Se podrá empezar por el principio, por el final (los últimos mensajes) o por un offset concreto, y pasar a la página siguiente.
- **CA-5.3:** Un offset fuera de rango se explicará indicando el rango válido.
- **CA-5.4:** Leer no cambiará nada: los mensajes seguirán ahí, se podrán leer tantas veces como se quiera y no se creará ningún consumer group. La pantalla lo explicará, porque es la gran diferencia con una cola de RabbitMQ.

### RF-6 — Interfaz
- **CA-6.1:** El laboratorio tendrá las pestañas **Topics**, **Producción** y **Mensajes**, con una explicación breve en cada una y el aspecto del Laboratorio RabbitMQ.
- **CA-6.2:** Los textos estarán en español, sin detalles técnicos en los errores, y la interfaz se podrá usar con el teclado.
- **CA-6.3:** El Laboratorio RabbitMQ seguirá funcionando igual.

### RF-7 — Pruebas
- **CA-7.1:** Pruebas unitarias del núcleo y de los controladores, sin Kafka ni Docker.
- **CA-7.2:** Pruebas de integración contra un Kafka real con Testcontainers, dentro de `./mvnw verify`.
- **CA-7.3:** Pruebas del frontend para cada pantalla, sus estados de carga, vacío y error, y sus validaciones.

### RF-8 — Documentación
- **CA-8.1:** El README explicará el laboratorio, un ejemplo paso a paso, el puerto, `KAFKA_REPLICAS` y los problemas frecuentes, y su arquitectura incluirá Kafka.
- **CA-8.2:** Se actualizará el manual del asistente, que hoy dice que Kafka no está disponible, con los apartados, tecnologías, puertos y comandos nuevos. Las herramientas y el diagnóstico de Kafka quedan para la especificación 11.

## 4. Requisitos no funcionales

- **RNF-1 (Memoria):** Kafka tendrá la memoria acotada; el consumo real se mide en T01 y se documenta.
- **RNF-2 (Arquitectura):** la lógica del laboratorio no dependerá del cliente de Kafka, que se usará a través de adaptadores, como con RabbitMQ.
- **RNF-3 (Tiempos):** con Kafka caído, las operaciones responderán con un error en menos de 10 s.
- **RNF-4 (Seguridad):** Kafka no se expondrá fuera de la máquina. Sin autenticación ni cifrado, porque es un laboratorio local.

## 5. Fuera de alcance

- Consumer groups, commits, rebalanceos y lag (especificación 09).
- Generador, visualizador de flujo y guía de Kafka (especificación 10).
- Herramientas del asistente y comparación con RabbitMQ (especificación 11).
- Varios brokers, replicación y failover.
- Cabeceras, compactación, retención configurable, transacciones y aumentar particiones.
- Schema Registry, Kafka Connect y Kafka Streams.

## 6. Criterios de finalización

1. Un `docker compose up` en una copia limpia levanta Kafka; con `KAFKA_REPLICAS=0`, todo menos Kafka.
2. Desde la interfaz se crea un topic, se produce con y sin clave, y se leen los mensajes por partición y offset.
3. Se comprueba que leer no retira los mensajes y que la misma clave va a la misma partición.
4. Desde «Estado» se para y se levanta Kafka, y el laboratorio se recupera solo.
5. Pasan las pruebas de todos los módulos, con las de las especificaciones anteriores.
6. El usuario lo confirma en Chrome.

## 7. Decisiones aprobadas

1. **Un solo broker**, sin replicación.
2. **Dependencia nueva:** `org.apache.kafka:kafka-clients`, sin Spring Kafka, igual que `amqp-client` con RabbitMQ.
3. **Imagen nueva:** `apache/kafka`, la oficial, con versión fijada. Última estable comprobada: `4.4.0`.
4. **Dependencia de pruebas nueva:** `org.testcontainers:testcontainers-kafka`.
5. **Cambio de contrato de la especificación 07:** `kafka` se añade a los servicios que se pueden parar y levantar, con el valor `KAFKA` en `ControlledService`.

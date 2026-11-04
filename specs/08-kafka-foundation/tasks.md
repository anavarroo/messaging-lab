# Tareas 08 — Base del Laboratorio Kafka

- **Estado:** Aprobado
- **Fecha:** 2026-11-02
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Una tarea solo se marca `[x]` cuando su «Hecho cuando» se ha ejecutado de verdad.

## Fase 0 — Decisiones técnicas

- [ ] **T01 — Prueba técnica de Kafka**
  Fijar la imagen y la versión de `kafka-clients` y de Testcontainers. Probar los listeners desde Docker y desde la máquina, medir memoria y arranque, y medir cuánto tardan los errores con el broker caído.
  *Hecho cuando:* `plan.md` §8 tiene las versiones, las medidas y los tiempos elegidos.

## Fase 1 — Infraestructura

- [ ] **T02 — Kafka en el Compose**
  Servicio `kafka`, volumen, `KAFKA_PORT`, `KAFKA_REPLICAS`, memoria y plantilla `.env.example`.
  *RF:* RF-1
  *Hecho cuando:* en un proyecto aparte, Kafka arranca sano, conserva un topic tras parar y levantar, lo borra con `down -v`, y con `KAFKA_REPLICAS=0` no se levanta.

## Fase 2 — Backend

- [ ] **T03 — Contrato**
  Operaciones de Kafka en `backend/api/api.yml` y `KAFKA` en el control de servicios.
  *RF:* RF-2 a RF-5
  *Hecho cuando:* `./mvnw compile` genera las interfaces y los modelos.

- [ ] **T04 — Núcleo de Kafka**
  Modelos, validación de nombres y datos, casos de uso y puertos, con un Kafka en memoria para las pruebas.
  *RF:* RF-3, RF-4, RF-5 (RNF-2)
  *Hecho cuando:* pasan las pruebas del núcleo, y `CoreIndependenceTest` lo incluye.

- [ ] **T05 — Adaptador: conexión y topics**
  Configuración, estado de conexión, y crear, listar, describir y eliminar topics.
  *RF:* RF-2, RF-3 (RNF-3)
  *Hecho cuando:* las pruebas de integración verifican cada operación, los topics internos ocultos, y el error y la recuperación con el broker caído.

- [ ] **T06 — Adaptador: producir y leer**
  Productor compartido y lectura con `assign` y `seek`, sin consumer group.
  *RF:* RF-4, RF-5
  *Hecho cuando:* las pruebas de integración verifican la partición y el offset devueltos, la misma clave en la misma partición, la partición elegida, la paginación, el offset fuera de rango, que leer no retira nada, y que no aparece ningún consumer group.

- [ ] **T07 — Controladores y errores**
  `KafkaController` y traducción de errores a `ApiError` en español.
  *RF:* RF-2 a RF-5
  *Hecho cuando:* las pruebas con MockMvc verifican cada operación y cada error.

- [ ] **T08 — Control de servicios**
  `KAFKA` en `ServiceControl`, con su mensaje cuando no existe.
  *RF:* RF-2 (CA-2.2)
  *Hecho cuando:* las pruebas verifican parar, levantar, el caso sin contenedor, y que RabbitMQ y el asistente no cambian.

## Fase 3 — Frontend

- [ ] **T09 — Armazón del laboratorio**
  Sección del menú habilitada, rutas, pestañas e indicador de conexión con recuperación automática.
  *RF:* RF-2, RF-6
  *Hecho cuando:* las pruebas verifican la navegación, el estado conectado y no disponible, y la recuperación sin recargar.

- [ ] **T10 — Pestaña Topics**
  Lista, creación con validación, detalle por partición y eliminación con confirmación.
  *RF:* RF-3
  *Hecho cuando:* las pruebas verifican cada estado, validación y error.

- [ ] **T11 — Pestaña Producción**
  Formulario con topic, clave, valor y partición, el resultado con enlace al log, y la explicación de las claves.
  *RF:* RF-4
  *Hecho cuando:* las pruebas verifican producir con y sin clave, los límites, el resultado y los errores.

- [ ] **T12 — Pestaña Mensajes**
  Lectura por partición, desde el principio, el final o un offset, con paginación y la explicación de que leer no retira.
  *RF:* RF-5
  *Hecho cuando:* las pruebas verifican cada modo, la paginación, el log vacío y el offset fuera de rango.

- [ ] **T13 — Tarjeta en «Estado»**
  Tarjeta de Kafka con `ServiceControls` y su confirmación.
  *RF:* RF-2 (CA-2.2)
  *Hecho cuando:* las pruebas verifican los estados, la confirmación y el caso sin Kafka. `npm test -- --watch=false` y `npx ng build` pasan.

## Fase 4 — Documentación y cierre

- [ ] **T14 — README, AGENTS y manual del asistente**
  Laboratorio, ejemplo paso a paso, puertos, problemas frecuentes, arquitectura y su diagrama, y las fichas del asistente.
  *RF:* RF-8
  *Hecho cuando:* los comandos del README coinciden con los reales, y el asistente responde que el Laboratorio Kafka está disponible, con sus pestañas. `uv run pytest` pasa.

- [ ] **T15 — Verificación completa**
  Pruebas de todos los módulos, levantamiento en una copia limpia en los dos modos y prueba en Chrome.
  *Hecho cuando:* se cumplen los criterios de finalización de la spec, y el usuario lo confirma en Chrome.

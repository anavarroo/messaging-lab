# Tareas 06 — Asistente de IA del laboratorio

- **Estado:** Aprobado
- **Fecha:** 2026-10-31
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Orden de dependencia. Una tarea solo se marca `[x]` cuando su «Hecho cuando» se ha ejecutado de verdad. El núcleo no puede depender de RabbitMQ, Ollama ni Spring (RNF-3).

## Fase 0 — Prueba técnica e infraestructura

- [x] **T01 — Probar qwen2.5:3b en este equipo**
  En un proyecto Compose aparte y con puertos alternativos, levantar Ollama con qwen2.5:3b y medir: memoria con el modelo cargado y descargado, tiempo hasta la primera palabra y velocidad, con un contexto de 3 000 tokens; y calidad en español con 5 contextos preparados a mano (una cola que crece, una sin consumidor, trazado apagado, una pregunta conceptual y una petición de acción). Anotar los resultados en `plan.md`.
  *RF:* RF-5; RNF-1, RNF-2
  *Hecho cuando:* `plan.md` tiene las cifras y las respuestas de ejemplo, y si RNF-1 o RNF-2 no se cumplen está decidido el ajuste (contexto, modelo o Ollama nativo).

- [x] **T02 — Servicio del modelo en el Compose**
  Añadir `ollama` y `ollama-model` a `infra/docker-compose.yml` (volumen, límites, descarga única, variables) y las variables comentadas a `infra/.env.example`, con la opción de **no levantar el modelo** (`OLLAMA_REPLICAS=0`).
  *RF:* RF-5
  *Hecho cuando:* en un proyecto aparte, `docker compose up -d` deja Ollama sano y el modelo descargado; un segundo `up` no vuelve a descargar; el puerto solo es accesible desde la máquina; el modelo se descarga de la memoria tras el tiempo configurado; y con `OLLAMA_REPLICAS=0` no se levanta nada del modelo y no hay errores.

## Fase 1 — Núcleo del backend

- [x] **T03 — Contrato del asistente**
  Añadir a `backend/api/api.yml` `GET /api/assistant/status` y `POST /api/assistant/chat` (SSE) con sus esquemas.
  *RF:* RF-1, RF-2, RF-6
  *Hecho cuando:* `./mvnw compile` genera la interfaz y los modelos sin errores.

- [x] **T04 — Foto del laboratorio y reglas**
  Crear `Snapshot`, `Finding` y `Diagnostics` con todas las reglas de CA-3.3, con pruebas.
  *RF:* RF-3 (CA-3.3); RNF-3, RNF-4
  *Hecho cuando:* las pruebas cubren cada regla con casos que la activan y que no, incluida la combinación de varias; cada conclusión está redactada como frase completa de causa y, si la hay, de solución (resultado de T01); y `CoreIndependenceTest` incluye el paquete `assistant`.

- [x] **T05 — Clasificador y manual**
  Crear `QuestionClassifier` y `KnowledgeBase` con las fichas del manual (los elementos de RabbitMQ y los de la aplicación).
  *RF:* RF-3 (CA-3.4)
  *Hecho cuando:* las pruebas verifican el tema asignado a un conjunto de preguntas reales, los casos ambiguos y vacíos, y que cada concepto de la guía tiene ficha y cabe en el presupuesto.

- [x] **T06 — Constructor de contexto**
  Crear `ContextBuilder`: conclusiones primero, datos relevantes resumidos, recorte al presupuesto de ~1 500 tokens (resultado de T01) y sin cuerpos de mensajes.
  *RF:* RF-3 (CA-3.1, CA-3.2, CA-3.5)
  *Hecho cuando:* las pruebas verifican que cada tema produce los datos esperados, que nunca supera el presupuesto aunque haya cientos de colas, que prioriza las conclusiones y que no aparece ningún cuerpo de mensaje.

- [x] **T07 — `AssistantService`**
  Componer el prompt (instrucciones de CA-3.6, contexto, historial), validar la pregunta, atender una sola petición a la vez, cancelar y limitar el tiempo, con puertos `SnapshotProvider` y `LlmClient` y fakes en las pruebas.
  *RF:* RF-2, RF-4, RF-6
  *Hecho cuando:* las pruebas verifican las instrucciones de solo lectura, el límite de 500 caracteres, el historial recortado, «ocupado» ante una segunda petición, la cancelación, el tiempo máximo y los errores en español.

## Fase 2 — Adaptadores y API

- [x] **T08 — Cliente de Ollama**
  Implementar `OllamaClient` (streaming de `/api/chat`, disponibilidad por `/api/tags`, opciones de contexto y longitud) con el cliente HTTP de Java.
  *RF:* RF-2, RF-5
  *Hecho cuando:* las pruebas con un servidor falso verifican el streaming completo, un corte a mitad, un error, el modelo no descargado (estado «preparando») y Ollama caído.

- [x] **T09 — Foto real del laboratorio**
  Implementar el `SnapshotProvider` con `BrokerTopology`, emisores, consumidores simulados, el flujo y el trazado; tolerante a que RabbitMQ no responda.
  *RF:* RF-3 (CA-3.1)
  *Hecho cuando:* las pruebas con fakes verifican la foto de un laboratorio con datos y la de uno con RabbitMQ caído.

- [x] **T10 — Controlador**
  Implementar `AssistantController` (estado y chat por SSE con `token`, `done` y `error`, y cierre al cancelar el cliente).
  *RF:* RF-1, RF-2, RF-6
  *Hecho cuando:* las pruebas verifican el flujo de eventos, los errores y el cierre, y una prueba de integración con RabbitMQ real y un `LlmClient` falso construye el contexto de una cola que crece de verdad.

## Fase 3 — Frontend

- [x] **T11 — Servicio del asistente**
  Crear `AssistantService`: estado, envío con lectura del flujo, cancelación, historial en memoria y «Nueva conversación».
  *RF:* RF-1 (CA-1.3, CA-1.4), RF-2
  *Hecho cuando:* las pruebas con un flujo falso verifican la respuesta por partes, la cancelación, los errores, el historial que sobrevive y los estados (preparando, no disponible, ocupado).

- [x] **T12 — Panel y botón**
  Crear `AssistantWidget`: icono en la cabecera, panel flotante, accesibilidad y estados.
  *RF:* RF-1 (CA-1.1, CA-1.2, CA-1.5, CA-1.6), RF-2
  *Hecho cuando:* las pruebas verifican abrir y cerrar (botón y Escape), el foco, el envío con Intro, el texto sin interpretar como HTML, los avisos de cada estado y que el campo se bloquea sin asistente.

- [x] **T12b — Estado del modelo en la pantalla «Estado»**
  Añadir a la pantalla «Estado» el estado del modelo del asistente (listo, descargándose, no disponible, con el motivo), con la misma comprobación y el mismo botón que el resto.
  *RF:* RF-2 (CA-2.4)
  *Hecho cuando:* las pruebas verifican los tres estados, el motivo cuando no está disponible y el botón «Volver a comprobar». `npm test -- --watch=false` y `npx ng build` pasan.

- [x] **T13 — Integración en la cabecera**
  Colocar el widget a la derecha de la cabecera sin afectar al menú ni a las pestañas fijas; ajustar las pruebas del *shell*.
  *RF:* RF-1 (CA-1.1)
  *Hecho cuando:* `npm test -- --watch=false` y `npx ng build` pasan, y el icono aparece en todas las pantallas.

## Fase 4 — Calidad y cierre

- [x] **T14 — Evaluación con el modelo real**
  Montar los escenarios (cola que crece, sin consumidor, trazado apagado, RabbitMQ caído, emisor fallado, dead-letter queue con mensajes) y ejecutar el conjunto de ~20 preguntas contra qwen2.5:3b; anotar resultados y corregir reglas, fichas o instrucciones.
  *RF:* todos; criterio de finalización 3 a 5
  *Hecho cuando:* las respuestas a las preguntas del conjunto son correctas y coherentes con lo que muestra la pantalla, o las desviaciones están documentadas, y una petición de acción responde con el lugar donde hacerla sin ejecutarla.

- [x] **T15 — README**
  Documentar el asistente, su coste de memoria, la primera descarga y cómo usar otro modelo o un Ollama nativo.
  *RF:* criterio de finalización 8
  *Hecho cuando:* el README lo cubre y sus comandos coinciden con los del Compose.

- [x] **T16 — Verificación completa**
  Ejecutar `./mvnw test`, `./mvnw verify`, `npm test -- --watch=false` y `npx ng build`; comprobar por HTTP los criterios 2, 4, 5 y 6 en un proyecto aparte, incluida la app sin el contenedor del modelo; y la prueba en Chrome.
  *RF:* todos
  *Hecho cuando:* todo pasa con las pruebas de las especificaciones 01 a 05 incluidas y los resultados están anotados.

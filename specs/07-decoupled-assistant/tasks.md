# Tareas 07 — Asistente de IA desacoplado, con herramientas

- **Estado:** Propuesta pendiente de revisión
- **Fecha:** 2026-10-31
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Una tarea solo se marca `[x]` cuando su «Hecho cuando» se ha ejecutado de verdad.

## Fase 0 — Decisiones técnicas

- [ ] **T01 — Prueba técnica: librerías y modelos**
  Fijar las librerías de Python y sus versiones. Medir uso de herramientas, acierto, memoria y latencia de 2-3 modelos pequeños y 1-2 de 7-8B con 10 preguntas del banco. Elegir el modelo por defecto, el grande, el de embeddings y dónde se guarda el índice.
  *Hecho cuando:* `plan.md` tiene los resultados y las decisiones.

## Fase 1 — Backend

- [ ] **T02 — Diagnóstico y actividad por la API**
  Añadir al contrato y al backend `GET /api/lab/diagnostics` y `GET /api/lab/activity` (solo lectura), sobre la foto del laboratorio y `Diagnostics`.
  *Hecho cuando:* las pruebas verifican las dos operaciones con datos, sin datos y con RabbitMQ caído, y que no devuelven cuerpos de mensajes.

## Fase 2 — Servicio del asistente (Python)

- [ ] **T04 — Módulo `assistant/`**
  Crear el proyecto (FastAPI y uv), con `GET /api/assistant/status` y `POST /api/assistant/chat` idénticos al contrato de la especificación 06, y el cliente de Ollama con streaming y cancelación.
  *Hecho cuando:* `pytest` pasa con pruebas del contrato, del flujo SSE (`token`, `done` y `error`), de los errores 400, 409 y 503, y de la cancelación.

- [ ] **T05 — Herramientas y enrutado directo**
  Herramientas de solo lectura sobre la API del backend, y respuestas directas (estado general, actividad y RabbitMQ caído) con el clasificador portado.
  *Hecho cuando:* las pruebas con un backend falso verifican el texto de cada herramienta, que solo se hacen GET, y las tres respuestas exactas sin llamar al modelo.

- [ ] **T06 — Agente con herramientas**
  Bucle de agente con máximo 4 llamadas y respaldo de contexto preparado si falla.
  *Hecho cuando:* las pruebas con un modelo falso verifican la elección y el encadenamiento de herramientas, el límite de llamadas, el respaldo y el tiempo máximo.

- [ ] **T07 — Búsqueda en la documentación**
  Trocear e indexar la guía, el README y las especificaciones con embeddings locales, con la herramienta de búsqueda.
  *Hecho cuando:* las pruebas verifican el troceado y que una pregunta conceptual recupera el trozo adecuado aunque no comparta palabras.

- [ ] **T08 — Estado del asistente**
  `READY`, `PREPARING` o `UNAVAILABLE` según Ollama, los dos modelos y el backend.
  *Hecho cuando:* las pruebas verifican cada estado y su mensaje en español.

## Fase 2b — Limpieza del backend

- [ ] **T03 — Quitar el asistente del backend (limpieza de la especificación 06)**
  Eliminar todo lo de la tabla del plan §5b: código, contrato, configuración, variables del Compose y pruebas que sobran. Mover la foto, las reglas y la actividad a un paquete `diagnostics`. Se hace **cuando el servicio en Python ya atiende el chat**, para que el asistente no deje de funcionar en medio.
  *Hecho cuando:* el backend no contiene lógica de IA ni referencias al modelo; `grep` de `assistant`, `Ollama` y `ASSISTANT_` en `backend/` solo encuentra lo que se queda a propósito; el contrato ya no tiene `/api/assistant`; y `./mvnw verify` pasa.

## Fase 3 — Infraestructura

- [ ] **T09 — Compose, nginx y proxy**
  Servicio `assistant`, variables y perfiles de modelo en `infra/.env.example`, descarga del modelo de embeddings, y `/api/assistant` enrutado en nginx y en el proxy de desarrollo.
  *Hecho cuando:* en un proyecto aparte, `docker compose up` levanta todo, el chat funciona sin cambios en el frontend, y con `OLLAMA_REPLICAS=0` se levanta todo menos el asistente sin errores.

## Fase 3b — Parar y levantar servicios desde «Estado» (RF-6)

- [ ] **T09b — Intermediario de Docker**
  Añadir `docker-proxy` al Compose (sin puertos publicados, solo listar, start y stop) y comprobar que no permite nada más.
  *Hecho cuando:* en un proyecto aparte, el backend puede listar, arrancar y parar contenedores a través de él, y una petición de otro tipo (crear, borrar o ejecutar) se rechaza.

- [ ] **T09c — Control en el backend**
  Contrato y operaciones `GET /api/control/services` y `POST /api/control/services/{servicio}/actions`, con la lista blanca del plan y la detección de «sin Compose».
  *Hecho cuando:* las pruebas verifican el estado de cada servicio, arrancar y parar, el rechazo de cualquier otro servicio o proyecto, el asistente parado junto con Ollama y el caso sin intermediario; y una prueba en un proyecto aparte para y levanta RabbitMQ de verdad.

- [ ] **T09d — Botones en «Estado»**
  Botones «Parar» y «Levantar» en las tarjetas de RabbitMQ y del asistente, con confirmación, estado intermedio, bloqueo y explicación cuando no hay control.
  *Hecho cuando:* las pruebas verifican la confirmación con sus consecuencias, el estado intermedio, el bloqueo, la nueva comprobación al terminar, los errores en español y la explicación sin Compose. `npm test -- --watch=false` y `npx ng build` pasan.

## Fase 4 — Evaluación y cierre

- [ ] **T10 — Evaluación automática**
  Banco de preguntas con escenarios y comprobaciones, y su ejecutor con `pytest`.
  *Hecho cuando:* el ejecutor funciona contra el stack y da el informe de acierto y latencia.

- [ ] **T11 — Medir y ajustar**
  Ejecutar la evaluación con el modelo por defecto y con el grande, y ajustar hasta los umbrales.
  *Hecho cuando:* se alcanza el 80 % con el modelo por defecto y el 90 % con el grande, o las desviaciones están documentadas en `plan.md`.

- [ ] **T12 — README y AGENTS.md**
  Documentar la arquitectura, los perfiles, la memoria, las descargas y la velocidad por sistema operativo, y los comandos del módulo `assistant/`.
  *Hecho cuando:* el README cubre CA-4.3 y los comandos de AGENTS.md y del README coinciden con los reales.

- [ ] **T13 — Verificación completa**
  Pruebas de todos los módulos, levantamiento en una copia limpia en los dos modos y prueba en Chrome.
  *Hecho cuando:* todo pasa con las pruebas de las especificaciones 01 a 06; desde «Estado» se para y se levanta RabbitMQ y el asistente en una copia limpia; y el usuario lo confirma en Chrome.

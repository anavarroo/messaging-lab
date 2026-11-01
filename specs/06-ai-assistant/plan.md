# Plan 06 — Asistente de IA del laboratorio

- **Estado:** Aprobado
- **Fecha:** 2026-10-31
- **Referencias:** [spec.md](spec.md)

## 1. Idea central: el modelo explica, el backend razona

```
 Pregunta ─► Clasificador ─► Constructor de contexto ─► Prompt ─► Ollama (qwen2.5:3b) ─► respuesta en streaming
              (por temas)     │ instantánea en vivo        ▲
                              │ reglas → conclusiones      │ instrucciones fijas + historial corto
                              └ ficha del manual
```

Un modelo de 3B parámetros falla al deducir, pero reformula bien lo que ya está dicho. Por eso el trabajo se reparte así: **lo que se puede calcular, se calcula en código** (determinista y probado) y **el modelo solo lo redacta**.

## 1b. Resultados de la prueba técnica (T01) y del Compose (T02)

Medido en este equipo (Docker Desktop con 10 CPU y 7,7 GB, CPU sin GPU), con Ollama 0.40.13 y qwen2.5:3b (1,93 GB de descarga).

- **Memoria:** el contenedor en reposo ocupa **~45-85 MiB**; con el modelo cargado (contexto de 4 096), **2,4-2,6 GiB**. Con `keep_alive` vencido el modelo se descarga y vuelve a ~44 MiB (comprobado). Cumple RNF-1 con holgura (límite de 4 GB).
- **Velocidad de generación:** **21-45 tokens por segundo** en CPU. Una respuesta corta (30 tokens) sale en 1-2 s.
- **Velocidad de lectura del contexto:** unos **120-160 tokens por segundo**. De ahí la latencia de la primera palabra: **0,5 s** con ~250 tokens, **6 s** con ~1 100 tokens y **29,5 s** con ~3 600 tokens. Una repetición exacta del mismo contexto tarda 0,1 s (caché del prompt). La primera petición tras cargar el modelo suma ~3 s de carga.
- **Consecuencia (RNF-2):** con 3 000 tokens no se cumplen los 15 s. **El presupuesto de contexto baja a ~1 500 tokens** (≈ 10 s de primera palabra en el peor caso) y `num_ctx` a 2 048, con respuestas de hasta ~200 tokens. Esto reduce también la memoria. Como el backend ya entrega conclusiones, no hace falta más contexto.
- **Calidad en español, 5 casos con contexto preparado a mano:** cola que crece ✔, trazado apagado ✔, concepto ✔ y petición de acción ✔ (respondió «Pulsa en la pestaña «Generador» el botón «Parar todo»» sin intentar hacerlo). **Un fallo**: ante «NINGÚN consumidor» el modelo contestó «todos los consumidores están agotados». Con la conclusión **redactada ya como causa y solución** («CAUSA: la cola «barra» no se vacía porque NO TIENE NINGÚN CONSUMIDOR… SOLUCIÓN: …») respondió bien.
- **Regla de diseño que sale de aquí:** cada `Finding` se redacta como **frase completa de causa y, cuando la haya, de solución**, lista para copiarse. No se le pasan negaciones ni cifras sueltas para que las interprete.
- **Compose (T02):** el servicio `ollama` (imagen fijada a `0.40.13`; **la imagen ocupa unos 7 GB** en disco) y `ollama-model` funcionan: un segundo `up` no vuelve a descargar (la comprobación `ollama list` lo detecta), el puerto solo se publica en `127.0.0.1` y el límite de memoria es de 4 GB. **`OLLAMA_REPLICAS=0` no levanta ninguno de los dos y no da error** (comprobado con el resto de servicios en marcha).

## 2. Backend `com.messaginglab.assistant`

### 2.1 Núcleo (sin Spring, sin RabbitMQ, sin Ollama; lo vigila `CoreIndependenceTest`)
- **`Snapshot`:** foto del laboratorio en un instante, neutral: estado del backend y del sistema de mensajería, estado del trazado, destinos (colas) con listos y pendientes, enrutadores (exchanges), conexiones entre ellos, emisores, consumidores simulados, ritmos y omitidos del flujo.
- **`Diagnostics`:** reglas deterministas que convierten una foto en **conclusiones** (`Finding`: gravedad, tema y frase en español). Las de CA-3.3. Es el corazón del sistema y se prueba con tablas de casos.
- **`QuestionClassifier`:** asigna a la pregunta uno o varios **temas** (estado, colas, exchanges y bindings, emisores, consumidores, flujo, trazado, concepto) por palabras clave, con un tema por defecto «general». Sin IA: determinista y explicable.
- **`KnowledgeBase`:** el manual de la aplicación (fichas breves por concepto) y su búsqueda por palabras clave.
- **`ContextBuilder`:** para los temas de la pregunta, compone un texto compacto —conclusiones primero, después los datos relevantes ya resumidos— y lo **recorta al presupuesto** de ~1 500 tokens (estimación por caracteres, con margen). Nunca incluye cuerpos de mensajes.
- **`AssistantService`:** valida la pregunta (500 caracteres), compone el prompt (instrucciones fijas + contexto + historial que quepa + pregunta), controla que solo haya una petición a la vez y entrega la respuesta por streaming. Maneja cancelación y el tiempo máximo.
- **Puertos:** `SnapshotProvider` (da la foto) y `LlmClient` (genera la respuesta en streaming y informa de su disponibilidad).

### 2.2 Adaptadores
- **`assistant.ollama`:** `OllamaClient` habla con la API de Ollama (`POST /api/chat` en streaming, `GET /api/tags` para saber si el modelo está descargado), con las opciones de contexto y de longitud de respuesta. Usa el cliente HTTP que ya incluye Java 21 (no se añaden dependencias).
- **`SnapshotProvider` real:** compone la foto con lo que ya existe: `BrokerTopology` (exchanges, colas, bindings), `EmitterService`, `SimulatedConsumerService`, `FlowService` (ritmos del último minuto) y el estado del trazado. Si RabbitMQ no responde, la foto lo dice (y las reglas lo convierten en conclusión) en vez de fallar.
- **Controlador y contrato:** `GET /api/assistant/status` (`READY`, `PREPARING`, `UNAVAILABLE` con motivo) y `POST /api/assistant/chat` (pregunta + historial corto) que responde por `text/event-stream` (eventos `token`, `done` y `error`).

## 3. Infraestructura

Cambios en `infra/docker-compose.yml`:

- **`ollama`:** imagen oficial de Ollama (versión fijada), puerto publicado solo en `127.0.0.1`, volumen para los modelos, `OLLAMA_KEEP_ALIVE=5m`, `OLLAMA_NUM_PARALLEL=1`, `OLLAMA_MAX_LOADED_MODELS=1`, límite de memoria y una comprobación de salud.
- **`ollama-model`:** servicio de un solo uso que descarga **qwen2.5:3b** cuando `ollama` está sano y termina; si el modelo ya está en el volumen, no hace nada.
- **`backend`:** recibe `ASSISTANT_URL`, `ASSISTANT_MODEL` y el tamaño del contexto. No depende de que el modelo esté listo para arrancar.
- **No levantar el modelo:** `deploy.replicas: ${OLLAMA_REPLICAS:-1}` en `ollama` y `ollama-model`; con `OLLAMA_REPLICAS=0` no se crean y el resto sigue igual.
- **Configuración:** en `infra/.env.example`, comentadas, las variables del asistente (incluida la URL para usar un Ollama instalado en el equipo).

## 4. Frontend

- **`features/assistant/` (o `layout/assistant`):**
  - **`AssistantService`:** envía la pregunta con `fetch` y lee el flujo `text/event-stream` (no se usa `EventSource` porque solo permite GET), con cancelación mediante `AbortController`. Mantiene el historial en memoria, a nivel de aplicación, para que sobreviva a la navegación.
  - **`AssistantWidget`:** botón con icono en la cabecera y panel flotante (rol de diálogo no modal, gestión de foco, Escape, `aria-live` para la respuesta). Muestra los estados: preparando, no disponible, ocupado, respondiendo, error y cancelado.
- **Shell:** la cabecera pasa a alinear el icono a la derecha; el menú y las pestañas siguen fijos como hasta ahora.
- **Pantalla «Estado»:** una fila «Asistente (modelo)» en el bloque de comprobaciones de RabbitMQ o en uno propio, con los estados listo, descargándose y no disponible, a partir de `GET /api/assistant/status`.
- Las respuestas se pintan con interpolación de Angular (texto), nunca como HTML.

## 5. Estrategia de pruebas
- **Reglas y contexto sin modelo:** tablas de casos por cada regla y por cada tema, límites del presupuesto, ausencia de cuerpos de mensaje.
- **Servicio:** con un `LlmClient` falso (una sola petición a la vez, cancelación, tiempo máximo, límites de la pregunta).
- **Adaptador de Ollama:** contra un servidor HTTP falso que emite el formato de streaming de Ollama (incluidos cortes y errores).
- **Frontend:** servicio con un flujo falso y componentes con tiempo simulado.
- **Calidad del modelo:** un conjunto fijo de ~20 preguntas con la respuesta esperada (conceptuales y de estado, con el laboratorio en situaciones montadas) que se ejecuta **contra el modelo real** y se anota; no es una prueba automática de las normales por su coste y por no ser determinista.

## 6. Riesgos
- **Latencia en CPU:** qwen2.5:3b dentro de Docker en un Mac no usa la GPU. Si la prueba técnica no cumple RNF-2: reducir contexto y respuesta, bajar a 1,5B, o usar un Ollama nativo (URL configurable).
- **Primera descarga (~2 GB):** el asistente lo indica; el resto de la app no se bloquea.
- **Español del modelo:** se evalúa en la prueba técnica y con el conjunto de preguntas.
- **Inyección de instrucciones:** por diseño no entran cuerpos de mensajes ni se dan herramientas; los nombres (colas, emisores) se presentan como datos entre marcas.
- **Manual duplicado:** las fichas del manual se escriben aparte de la guía del frontend; son más cortas y cada una apunta a la sección de la guía correspondiente.

## 9. Evaluación con el modelo real (T14)

20 preguntas contra qwen2.5:3b con un laboratorio montado (cola que crece, cola sin consumidor, dead-letter queue con mensajes, emisor con mensajes sin enrutar), más trazado apagado, emisor fallado y RabbitMQ caído. Primera palabra entre 0,1 y 6 s.

- **Bien desde el principio:** conceptos (binding, dead-letter queue, direct frente a topic, exchange por defecto, trazado), cola que crece, cola sin consumidor, dead-letter queue, emisores en marcha, consumidor de una cola, mensajes listos, límite global, trazado apagado y pregunta ajena al laboratorio («No lo sé con los datos que tengo»).
- **Corregido tras la evaluación:**
  - «¿Por qué hay mensajes sin enrutar?» → el clasificador no lo asociaba a los emisores (respondía «No lo sé»).
  - Peticiones de acción («Para todos los emisores», «Borra la cola barra») → listaba datos. Ahora se detectan (tema `ACTION`) y el contexto solo lleva la ficha «Dónde se hace cada cosa»: responde «No puedo hacer eso… botón «Parar todo» en «Generador»».
  - «¿Está todo bien?» → el modelo de 3B mezclaba o negaba las conclusiones. **El resumen general lo da el backend sin el modelo** (lista de conclusiones), y también cualquier pregunta sobre el laboratorio con RabbitMQ caído. Es instantáneo y siempre correcto.
  - El emisor fallado se explica bien; RabbitMQ caído ya no repite el motivo.
- **Límite conocido:** las respuestas del modelo son correctas pero escuetas (por ejemplo, «100» al preguntar por el límite).

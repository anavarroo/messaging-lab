# Plan 07 — Asistente de IA desacoplado, con herramientas

- **Estado:** Aprobado
- **Fecha:** 2026-10-31
- **Referencias:** [spec.md](spec.md) · [especificación 06](../06-ai-assistant/spec.md)

## 1. Arquitectura

```
                 ┌──────────────────── red interna de Docker ────────────────────┐
 navegador ─► frontend (nginx) ─/api/assistant─► assistant (Python, FastAPI) ─► ollama (chat + embeddings)
                   │                                   │ herramientas: solo GET
                   └─/api/*──────────────────► backend (Java) ◄─┘
                                                  └─► RabbitMQ
```

- **`assistant/`** (módulo nuevo del monorepo, Python): sirve el contrato `/api/assistant/*` (el mismo de la especificación 06, así que el frontend no cambia), enruta las respuestas directas, ejecuta el agente con herramientas y busca en la documentación.
- **`backend/`** (Java): **elimina** el paquete `assistant` de la especificación 06 y **añade tres operaciones de lectura**:
  - `GET /api/lab/diagnostics`: las conclusiones de `Diagnostics`.
  - `GET /api/lab/activity`: lo que se mueve ahora.
  - `GET /api/lab/snapshot`: la foto del laboratorio ya resumida, sin cuerpos de mensajes (añadida en la fase 2 y aprobada: `/api/rabbitmq/queues` no da consumidores ni ritmos).

  La foto del laboratorio (`RabbitMqSnapshotProvider`) y las reglas se quedan, porque son del laboratorio, no IA. **No hay dependencias nuevas en Java.**
- **`frontend/`**: sin cambios de código. nginx y el proxy de desarrollo envían `/api/assistant` al nuevo servicio.

## 2. Servicio del asistente en Python

- **Base:** Python 3.12, **FastAPI** con streaming por SSE y **uv** para las dependencias y el entorno.
- **Modelos:**
  - **Chat:** cliente oficial de Ollama, con llamadas a herramientas.
  - **Embeddings:** un modelo pequeño de Ollama, del orden de 300 MB, para la búsqueda en la documentación.
- **Agente:** un bucle propio y corto (pregunta, herramientas y respuesta, con un máximo de 4 llamadas). Es suficiente y evita depender de un framework grande. Si la prueba técnica muestra que una librería de agentes aporta algo claro (por ejemplo, pydantic-ai), se propone entonces.
- **Herramientas:** funciones de solo lectura que llaman por GET al backend y devuelven texto en español, resumido y acotado:
  - estado del laboratorio;
  - colas;
  - exchanges;
  - generador;
  - actividad;
  - diagnóstico;
  - búsqueda en la documentación.
- **Enrutado previo, sin IA:** el clasificador de la especificación 06, portado a Python. El estado general, la actividad y las preguntas con RabbitMQ caído se responden con el texto exacto de la herramienta.
- **Respaldo:** si el modelo no usa herramientas o falla al usarlas, se monta un contexto preparado (el `ContextBuilder` de la especificación 06, portado).
- **Búsqueda en la documentación:**
  - **Documentos:** guía (exportada como texto), README y especificaciones, troceados por secciones.
  - **Índice:** embeddings en un índice en memoria (búsqueda por similitud con NumPy), o en un volumen si tarda mucho en construirse.
- **Pruebas:** `pytest`, con un Ollama y un backend falsos para la lógica, y la evaluación automática contra el stack real.
- **Dependencias nuevas** (aprobadas en la decisión de lenguaje): FastAPI con su servidor, cliente de Ollama, cliente HTTP, NumPy y pytest. La lista exacta y sus versiones se fijan en T01.

## 3. Infraestructura y perfiles

- **Compose:** servicio `assistant` nuevo, con imagen de Python construida desde `assistant/`. Las variables `ASSISTANT_*` pasan del backend al asistente, y `ollama-model` descarga también el modelo de embeddings.
- **Perfiles de modelo** en `infra/.env.example`:
  - **Por defecto:** pequeño, el que mejor use herramientas en T01.
  - **Grande:** de 7-8B, indicando la memoria que hay que dar a Docker.
  - **Ollama nativo:** `ASSISTANT_OLLAMA_URL=http://host.docker.internal:11434`.
- **Sin asistente:** `OLLAMA_REPLICAS=0` deja fuera Ollama y el asistente.

## 4. Evaluación automática

- **Banco de preguntas:** en un archivo de datos, con su escenario y las frases o cifras que deben aparecer o no aparecer en la respuesta.
- **Ejecutor:** `pytest` con una marca propia, que monta los escenarios con la API del backend, pregunta por `/api/assistant/chat` y da el porcentaje de acierto y la latencia.
- **Umbrales:** 80 % con el modelo por defecto y 90 % con el grande.

## 5b. Limpieza de la especificación 06 en el backend

Cuando el servicio en Python atienda el chat (T03, después de T04 a T08), se **elimina del backend todo lo que deja de usarse**, para no dejar código muerto:

| Se elimina | Motivo |
|---|---|
| Paquete `assistant`: `AssistantService`, `QuestionClassifier`, `KnowledgeBase`, `ContextBuilder`, `Words`, `Message`, `LlmClient`, `LlmStatus`, `Cancellation`, `SnapshotProvider` y las excepciones del asistente | **Se portan a Python con el mismo contenido**: las 20 fichas del manual (incluida «Dónde se hace cada cosa»), las instrucciones del modelo, el clasificador y el contexto preparado de respaldo. Las piezas de fontanería (puertos, cancelación, excepciones) se rehacen en Python. |
| `assistant.ollama`: `OllamaClient`, `AssistantProperties`, `AssistantConfiguration` | El backend ya no habla con Ollama. |
| `AssistantController` y los manejadores de errores del asistente en `ApiExceptionHandler` | El chat lo sirve el servicio del asistente. |
| Del contrato `backend/api/api.yml`: las operaciones `/api/assistant/*`, sus esquemas, la etiqueta `Assistant` y el código `ASSISTANT_UNAVAILABLE`; y en `pom.xml`, el mapeo de `AssistantEventStream` | El contrato pasa a `assistant/` (el mismo, sin cambios para el frontend). |
| `messaginglab.assistant.*` en `application.properties` y `ASSISTANT_*` del servicio `backend` en el Compose | Las variables pasan al servicio `assistant`. |
| Pruebas: las del paquete `assistant`, `OllamaClientTest`, `AssistantControllerTest` y `AssistantIT`; la entrada del asistente en `CoreIndependenceTest` | Se portan a `pytest` las que siguen teniendo sentido (clasificador, contexto y servicio). |

**Ninguna información fija del proyecto se pierde.** **Se queda en el backend**, porque es del laboratorio y no de IA: la foto (`Snapshot` y `RabbitMqSnapshotProvider`) y las reglas (`Diagnostics`, `Finding` y `Topic`), movidas a un paquete `diagnostics`, más el cálculo de la actividad. Todo ello sirve `GET /api/lab/diagnostics` y `GET /api/lab/activity`, con sus pruebas actuales.

## 6. Parar y levantar servicios desde «Estado» (RF-6)

```
 frontend ─► backend ─/control─► docker-proxy (solo: listar, start, stop) ─► /var/run/docker.sock
```

- **Por qué un intermediario:** montar el socket de Docker en el backend equivale a darle control total de la máquina. En su lugar se añade un servicio `docker-proxy` (una imagen de *docker socket proxy*, por ejemplo `tecnativa/docker-socket-proxy`, a confirmar en T01) que solo deja **listar contenedores y arrancarlos o pararlos**, y que no publica ningún puerto: solo lo ve el backend por la red interna de Docker. Es una **imagen nueva de terceros** y necesita aprobación.
- **Reglas propias del intermediario (decidido en T09b):** con la configuración de la imagen `v0.5.0`, permitir arrancar y parar obliga a activar `POST`, y entonces también se podrían crear, borrar o ejecutar comandos en contenedores. Por eso se monta un `haproxy.cfg.template` propio (`infra/docker-proxy/`) con solo tres reglas: listar contenedores, arrancar y parar. Inspeccionar tampoco se permite (devolvería las variables de entorno de los contenedores): el backend encuentra su proyecto filtrando la lista por su propio id. El intermediario está en una red interna que solo comparte con el backend.
- **Lista blanca en el backend:** solo actúa sobre los contenedores de **este proyecto Compose** (etiqueta `com.docker.compose.project`) cuyo servicio sea `rabbitmq`, o `assistant` y `ollama` (el asistente se para y se levanta junto con Ollama). Cualquier otro nombre se rechaza aunque el intermediario lo permitiese.
- **Contrato del backend:**
  - `GET /api/control/services`: para cada servicio controlable, su estado (en marcha, parado o no existe) y si se puede controlar.
  - `POST /api/control/services/{rabbitmq|assistant}/actions` con `START` o `STOP`.
- **Implementación:** con el cliente HTTP de Java que ya se usa, contra la API de Docker del intermediario. No hay dependencias nuevas en Java.
- **Sin Compose:** si el backend no encuentra el intermediario (arranque local), responde que el control no está disponible y el frontend oculta los botones y muestra el comando equivalente.
- **Frontend:** botones «Parar» y «Levantar» en las dos tarjetas de «Estado», con el diálogo de confirmación que ya existe, estado intermedio («arrancando…» o «parando…») y nueva comprobación al terminar.
- **RabbitMQ:** al pararse, el backend ya informa de que no está disponible y se recupera solo al volver (especificaciones 02 y 05). El trazado se reactiva solo gracias al guion del Compose.

## 5. Riesgos

- **Uso de herramientas con modelos pequeños:** es flojo. Lo cubren el enrutado previo para lo crítico, el respaldo de contexto preparado y la medición en T01.
- **Un segundo lenguaje en el monorepo:** se documenta en AGENTS.md y el README, con sus comandos de prueba y arranque.
- **Memoria del perfil grande:** dentro de Docker en un Mac de 8 GB no cabe. Se documenta y se recomienda Ollama nativo.
- **Lógica portada:** el clasificador y el `ContextBuilder` pasan de Java a Python con sus casos de prueba.
- **Acceso a Docker:** se limita con el intermediario y la lista blanca; aun así, el README advierte de que es una función pensada para un equipo de desarrollo local.

## 7. Resultados de la prueba técnica (T01)

Medido en este equipo (Docker con 10 CPU y 7,7 GB, solo CPU), con Ollama 0.40.13 aislado.

### 7.1 Uso de herramientas: 11 preguntas (10 de datos y conceptos y 1 ajena)

| Modelo | Herramientas «puras» (el modelo decide) | **Híbrido** (el servicio adelanta los datos y el modelo puede pedir más) | RAM cargado | Tiempo medio |
|---|---|---|---|---|
| qwen2.5:3b | 54 % | **90 %** | 2,4-2,5 GB | 1,7 s |
| llama3.2:3b | 72 % | 81 % | 3,0 GB | 3,5 s |
| qwen2.5:7b | 72 % | 81 % | 4,9-5,1 GB | 4,3 s |

- **Herramientas «puras» no bastan:** los tres modelos, el de 7B incluido, a menudo **no llaman a ninguna herramienta** y contestan «No lo sé», o eligen una que no toca (por ejemplo, `actividad` para preguntar por el límite).
- **Decisión: modo híbrido.** El clasificador (portado de la especificación 06) decide qué herramientas consultar **antes** de preguntar al modelo, y le pasa sus resultados junto con `diagnostico`. El modelo conserva las herramientas por si necesita más. Es la combinación de las especificaciones 06 y 07 y la que mejor resultado da.
- **El modelo de 7B no mejora al de 3B** en esta prueba, y gasta el doble de memoria y tiempo. **Por defecto: `qwen2.5:3b`.** El perfil grande (`qwen2.5:7b`) queda documentado como opcional, pero no recomendado mientras T11 no muestre que aporta algo. Con 11 preguntas la muestra es pequeña; T11 lo comprobará con el banco completo.
- **Aviso para CA-5.3:** el 90 % exigido al perfil grande puede no alcanzarse si no mejora al pequeño. Si T11 lo confirma, se propondrá ajustar ese criterio.

### 7.2 Búsqueda en la documentación: 10 preguntas en español, 38 trozos (README y fichas del manual)

| Modelo de embeddings | Acierta el 1.º | Entre los 3 primeros | Indexar 38 trozos | RAM |
|---|---|---|---|---|
| nomic-embed-text (0,27 GB) | 4/10 | 6/10 | 3,8 s | — |
| **bge-m3** (1,16 GB, multilingüe) | **8/10** | **10/10** | 8,1 s | 1,25 GB |

- **Decisión: `bge-m3`**, porque el contenido está en español y nomic-embed-text está pensado para inglés. Se recuperan los 3 mejores trozos.
- **Memoria con los dos modelos cargados** (`OLLAMA_MAX_LOADED_MODELS=2`): **3,5 GB**. Una consulta de embeddings tarda 0,2 s. Cabe en el presupuesto de 4 GB de RNF-2, aunque justo; se vigila en T09.
- **Índice:** indexar unos 200 trozos (guía, README y especificaciones) llevaría del orden de 40 s. **Decisión:** se construye al arrancar, en segundo plano, y se guarda en un volumen con un hash del contenido. Si la documentación no cambia, se reutiliza al momento. Mientras se construye, el estado del asistente es «preparándose».

### 7.3 Librerías y herramientas (versiones fijadas)

- **Python 3.12.** Las dependencias se gestionan con **uv**, sobre la imagen `ghcr.io/astral-sh/uv:python3.12-bookworm-slim`.
- **Dependencias:** `fastapi 0.162.0`, `uvicorn 0.54.0`, `httpx 0.28.1` (llamadas al backend), `ollama 0.6.3` (cliente oficial, con herramientas y streaming), `numpy 2.5.4` (índice de embeddings), `pytest 9.2.0` y `pytest-asyncio 1.4.1`.
- **Streaming:** el flujo SSE se hace con `StreamingResponse` de FastAPI, sin librería adicional.
- **Sin framework de agentes:** el bucle es propio y corto. Ninguno resolvía el problema que ha mostrado la prueba (que el modelo no llama a las herramientas); lo resuelve el modo híbrido.
- **Intermediario de Docker (RF-6):** `tecnativa/docker-socket-proxy:v0.5.0`.
- **Desarrollo local:** este equipo tiene Python 3.9 y no tiene uv. Las pruebas del asistente se pueden ejecutar sin instalar nada con `docker run` sobre la imagen de uv, o instalando uv (`brew install uv`), que descarga Python 3.12 solo. Se documenta en T12.

### 7.4 Evaluación y ajustes (T10 y T11)

Banco de 35 preguntas en 8 escenarios (`assistant/evaluation/`), contra un stack dedicado. Comando: `uv run pytest -m evaluation tests/test_evaluation.py -s` (ver el README).

| Modelo | Acierto | Primera palabra (mediana / máx.) | Respuesta completa (mediana / máx.) | Memoria de Ollama |
|---|---|---|---|---|
| qwen2.5:3b (por defecto) | **94 %** (33/35; antes de ajustar, 71 %) | 3,1 s / 6,3 s | 4,2 s / 9,3 s | 3,9 GB (límite 4 GB) |
| qwen2.5:7b (grande) | **97 %** (34/35) | 6,3 s / 29,5 s | 15,3 s / 37,4 s | 5,7 GB (límite 6,5 GB) |

Se cumplen los dos umbrales de CA-5.3 (80 % y 90 %). El modelo grande acierta algo más, pero es unas 3 veces más lento y necesita unos 6,5 GB solo para Ollama; en un equipo de 8 GB cabe muy justo. Sigue siendo opcional.

**Ajustes de T11** (de 71 % a 94 % con el modelo por defecto):
- **Peticiones de acción:** con herramientas, el modelo se ponía a consultar en vez de decir dónde se hace. Ahora se responden **sin el modelo**, con la ficha «Dónde se hace cada cosa» filtrada por el tema (exactas y en menos de 2 s).
- **Bloques de código:** con herramientas activas, Ollama se traga los bloques de código y la respuesta se cortaba justo antes del comando. Las instrucciones piden escribir los comandos entre «».
- **Respuestas de una palabra** («SI») y preguntas de concepto contestadas con los datos del laboratorio: las instrucciones piden responder a la pregunta, explicar los conceptos con el manual y la documentación aunque el laboratorio no los tenga, y no contestar solo «sí» o «no».
- **Documentación para todas las preguntas:** 3 trozos si son de conceptos y 1 si no (antes, solo para conceptos).
- **Tiempo de espera al backend: 15 s** (antes, 5 s). Justo después de pararse RabbitMQ, el backend tarda unos segundos en responder, y el asistente decía, sin razón, que el backend no respondía.
- **Escenario del trazado apagado:** el Compose vuelve a activar el trazado cada 15 s, así que la evaluación lo apaga antes de cada pregunta.

**Ampliación del manual (después de T11):**
- **Fichas nuevas:** «La aplicación y sus apartados», «Tecnologías y versiones», «Arquitectura», «Cómo está hecho el proyecto», «Parar, levantar y reiniciar servicios», «Cambiar los puertos» y «Cambiar el modelo de IA».
- **Preguntas sobre el proyecto:** se tratan como conceptos, con el manual y la documentación y sin los datos del laboratorio.
- **Cómo hacer algo** (cambiar puertos o el modelo, reiniciar): se responde **sin el modelo**, con el texto exacto de la ficha, porque el modelo pequeño cambiaba los nombres de las variables y se inventaba comandos.
- **Sobre el propio asistente:** fichas «Cómo funciona el asistente», «De dónde saca la información», «Cómo está levantado», «Límites» y «Si no responde o va lento». Las instrucciones dicen qué modelos usa de verdad (los de la configuración), y piden dar completas las listas del manual. «¿Qué apartados tiene?» se responde con la lista exacta, sin el modelo.
- **Banco de evaluación:** 9 preguntas más de tipo «proyecto» (44 en total). Con el modelo real, las de proyecto y conceptos aciertan 34 de 36 en dos pasadas (los dos fallos, en los apartados, ya se responden sin el modelo).
- **Búsqueda en la documentación:** ya no indexa las secciones de historia de las especificaciones («Contexto y objetivo», «Fuera de alcance», «Decisiones»…). El modelo las tomaba por el estado actual y decía, por ejemplo, que el Laboratorio RabbitMQ estaba deshabilitado.

**Fallos que quedan** (varían de una ejecución a otra; con temperatura 0,2 hay variación): alguna respuesta incompleta sobre los emisores en marcha o sobre las dead-letter queues.

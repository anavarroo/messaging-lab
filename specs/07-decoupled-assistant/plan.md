# Plan 07 — Asistente de IA desacoplado, con herramientas

- **Estado:** Propuesta pendiente de revisión
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
- **`backend/`** (Java): **elimina** el paquete `assistant` de la especificación 06 y **añade dos operaciones de lectura**:
  - `GET /api/lab/diagnostics`: las conclusiones de `Diagnostics`.
  - `GET /api/lab/activity`: lo que se mueve ahora.

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

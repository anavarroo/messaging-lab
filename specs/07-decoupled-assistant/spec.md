# Especificación 07 — Asistente de IA desacoplado, con herramientas

- **Estado:** Propuesta pendiente de revisión
- **Fecha:** 2026-10-31
- **Referencias:** [especificación 06](../06-ai-assistant/spec.md)

## 1. Contexto y objetivo

El asistente de la especificación 06 vive dentro del backend y recibe un contexto que el backend prepara adivinando el tema de la pregunta por palabras clave. Funciona, pero tiene dos límites:

- **Cuando no se acierta el tema, el modelo no tiene los datos.** Por ejemplo, «¿hay algo pasando datos?» no encajaba en ningún tema y el modelo respondió mal.
- **El backend mezcla lógica de IA con la del laboratorio.** Cambiar de modelo, añadir Kafka o mejorar el asistente obliga a tocar el backend.

**Objetivo:** convertir el asistente en un **servicio independiente**, en su propio contenedor, que:

1. **Consulta los datos reales cuando los necesita**, mediante **herramientas de solo lectura** que llaman a la API REST del backend.
2. **Busca en la documentación por significado** (RAG con embeddings) en lugar de por palabras clave.
3. **Conserva las respuestas exactas del backend** para lo crítico: estado general, actividad y RabbitMQ caído.
4. Permite **elegir el tamaño del modelo** según la máquina, y se mide con una **evaluación automática**.

Todo sigue siendo **local, sin claves, de solo lectura y arrancable con un único `docker compose up`**, para poder pasar el proyecto a otros usuarios.

## 2. Usuarios

- Los de la especificación 06.
- **Quien recibe el proyecto** y lo levanta en su máquina, con más o menos memoria.

## 3. Requisitos

### RF-1 — Un servicio aparte
- **CA-1.1:** El asistente será un servicio propio con su propio contenedor. El backend no contendrá lógica de IA (ni prompts, ni clasificación, ni llamadas al modelo): **todo lo de la especificación 06 que sobre en el backend se elimina** (código, contrato, configuración y pruebas), conservando solo la foto del laboratorio, las reglas de diagnóstico y la actividad.
- **CA-1.2:** El frontend no cambia de contrato: sigue usando `GET /api/assistant/status` y `POST /api/assistant/chat` (flujo `token`, `done` y `error`), que ahora atiende el servicio del asistente. El icono, el panel y la tarjeta de «Estado» siguen funcionando igual.
- **CA-1.3:** Si el servicio del asistente o el modelo no están, el resto de la aplicación funciona igual y el asistente se muestra como no disponible.

### RF-2 — Herramientas de solo lectura
- **CA-2.1:** El asistente tendrá herramientas **de solo lectura**: estado de RabbitMQ y del trazado; colas con listos, pendientes, consumidores y ritmos; exchanges con sus bindings; emisores y consumidores simulados; actividad actual; y **diagnóstico** (las conclusiones de las reglas de la especificación 06). Se apoyan en la **API REST que ya tiene el backend**.
- **CA-2.2:** Ninguna herramienta podrá cambiar nada (solo hacen consultas GET) ni devolverá cuerpos de mensajes.
- **CA-2.3:** El backend añadirá solo dos operaciones de lectura a su contrato: **diagnóstico** y **actividad**, que reutilizan las reglas ya escritas y probadas en Java. El resto de datos sale de las operaciones existentes.
- **CA-2.4:** Las herramientas devolverán los datos ya resumidos y en español, listos para leer, con un tamaño acotado.
- **CA-2.4b:** Las herramientas se definen en el servicio del asistente; cuando llegue Kafka se añadirán las suyas sobre su API, sin cambiar el resto del asistente.

### RF-3 — Cómo responde el asistente
- **CA-3.1:** Para cada pregunta, el modelo decidirá qué herramientas usar, como mucho **4 llamadas** por respuesta, y responderá con lo que devuelvan.
- **CA-3.2:** Seguirán respondidas **directamente, sin el modelo**, con el resultado exacto de las herramientas: el estado general («¿está todo bien?»), la actividad («¿hay algo pasando datos?») y cualquier pregunta sobre el laboratorio con RabbitMQ caído.
- **CA-3.3:** Para preguntas sobre conceptos, buscará en la documentación del proyecto (guía, README y especificaciones) **por significado**, con un modelo de embeddings local.
- **CA-3.4:** Se mantienen las reglas de la especificación 06: español, solo lectura, no inventar, «no lo sé con los datos que tengo», peticiones de acción respondidas con dónde se hacen, una pregunta a la vez y tiempo máximo.
- **CA-3.5:** Si el modelo no sabe usar herramientas, o falla al usarlas, se recurre a un contexto preparado como el de la especificación 06, para no dejar al usuario sin respuesta.

### RF-4 — Tamaño del modelo según la máquina
- **CA-4.1:** El modelo será configurable en `infra/.env`. El valor **por defecto** será uno pequeño que funcione en casi cualquier equipo; habrá un perfil **grande** documentado (7-8B) para quien tenga memoria.
- **CA-4.2:** Se podrá usar un **Ollama instalado fuera de Docker** (más rápido en Mac) cambiando solo una URL, y arrancar **sin el asistente** con `OLLAMA_REPLICAS=0`.
- **CA-4.3:** El README indicará, para cada perfil, la memoria que hay que dar a Docker, la descarga inicial y qué velocidad esperar en Mac, Linux y Windows.

### RF-5 — Evaluación automática
- **CA-5.1:** Habrá un conjunto fijo de preguntas, con escenarios montados (cola que crece, cola sin consumidor, dead-letter queue con mensajes, mensajes sin enrutar, trazado apagado, emisor fallado, RabbitMQ caído, preguntas de conceptos, peticiones de acción y preguntas ajenas), y para cada una los datos que la respuesta debe contener o no contener.
- **CA-5.2:** Un comando lo ejecutará contra el stack levantado y dará un informe con el acierto por pregunta y el total, y la latencia.
- **CA-5.3:** Con el modelo por defecto, el acierto será de al menos el **80 %**; con el perfil grande, de al menos el **90 %**. Los resultados se anotan en el plan.

### RF-6 — Parar y levantar RabbitMQ y el asistente desde «Estado»
- **CA-6.1:** En la pantalla «Estado», la tarjeta de RabbitMQ y la del asistente de IA tendrán un botón para **parar** y otro para **levantar** su servicio. Parar el asistente para su servicio y el de Ollama, y libera la memoria del modelo.
- **CA-6.2:** Antes de parar se pedirá confirmación, explicando las consecuencias:
  - **RabbitMQ:** el laboratorio deja de funcionar, se detienen los consumidores, el generador y el flujo, y los mensajes guardados se conservan.
  - **Asistente:** el chat deja de estar disponible.
- **CA-6.3:** Mientras arranca o se detiene, la tarjeta lo indicará, y los botones estarán bloqueados. Al terminar, el estado se actualizará solo. El resto de la aplicación se recupera sola cuando RabbitMQ vuelve, como ya hace.
- **CA-6.4:** Solo se podrá actuar sobre **RabbitMQ y el asistente** (con su Ollama), nunca sobre el backend, el frontend ni otros contenedores de la máquina.
- **CA-6.5:** Solo funcionará cuando la aplicación se ejecuta con el Docker Compose del proyecto. Si no (arranque local por componentes, o con `OLLAMA_REPLICAS=0` para el asistente), los botones no se mostrarán y la tarjeta explicará por qué y qué comando usar.
- **CA-6.6:** Los errores se explicarán en español, sin detalles técnicos.

## 4. Requisitos no funcionales

- **RNF-1 (Portabilidad):** todo se levanta con `docker compose up`; sin claves; nada sale de la máquina.
- **RNF-2 (Memoria):** con el modelo por defecto, el servicio del asistente más Ollama no superarán **4 GB**; con el perfil grande, **8 GB**.
- **RNF-3 (Latencia):** primera palabra en menos de **15 s** con el modelo por defecto en CPU; las respuestas directas, en menos de **2 s**.
- **RNF-4 (Arquitectura):** el modelo, el servidor de herramientas y el sistema de mensajería son intercambiables; el servicio del asistente no depende de RabbitMQ.
- **RNF-5 (Seguridad):** el asistente es de solo lectura; ningún cuerpo de mensaje llega al modelo; el texto de las herramientas se trata como datos.
- **RNF-6 (Control de contenedores):** el acceso a Docker para parar y levantar servicios se limita a esas dos operaciones sobre los contenedores de este proyecto. El backend nunca tendrá acceso directo a Docker; lo pide a un intermediario que solo permite eso, y que no es accesible desde fuera de la red de Docker.

## 5. Fuera de alcance

- Acciones sobre el laboratorio.
- Modelos de pago o en la nube.
- Reentrenar o ajustar modelos.
- Memoria entre sesiones.
- Las herramientas de Kafka (llegarán con su laboratorio).

## 6. Criterios de finalización

1. El asistente es un servicio aparte y el backend no contiene lógica de IA.
2. El backend expone el diagnóstico y la actividad como operaciones de lectura, y el asistente consulta solo por GET.
3. El chat y la tarjeta de «Estado» funcionan igual que antes, sin cambios de contrato.
4. La evaluación automática alcanza el 80 % con el modelo por defecto y el 90 % con el grande.
5. Las respuestas directas (estado general, actividad, RabbitMQ caído) siguen siendo exactas.
6. Un `docker compose up` en una copia limpia levanta todo; con `OLLAMA_REPLICAS=0`, todo menos el asistente.
6b. Desde «Estado» se para y se levanta RabbitMQ y el asistente, con confirmación, y no se puede actuar sobre ningún otro contenedor.
7. Las pruebas de todos los módulos pasan, con las de las especificaciones 01 a 06.
8. El README documenta perfiles, memoria, descargas y velocidad por sistema.

## 7. Decisiones tomadas

1. Servicio del asistente aparte; el backend solo expone datos.
7. Imagen de terceros `docker socket proxy` aprobada para RF-6 (parar y levantar contenedores desde el frontend).
2. Herramientas de solo lectura sobre la API REST del backend (sin MCP en el backend ni dependencias nuevas en Java).
6. **Python con FastAPI** para el servicio del asistente: el ecosistema de IA (cliente de Ollama, agentes, embeddings, evaluación) es más completo y permite iterar prompts y modelos más rápido. FastAPI frente a Flask por el streaming nativo, la validación con tipos y el contrato OpenAPI.
3. Se conservan las respuestas exactas del backend para lo crítico.
4. RAG local con embeddings sobre la documentación del proyecto.
5. Modelo pequeño por defecto y perfil grande opcional.

## 8. Puntos a decidir en el plan

- **Librerías concretas de Python** (cliente de Ollama, agente, índice de embeddings) y su versión, en la prueba técnica.
- **Modelo por defecto y modelo grande**, según la prueba técnica de uso de herramientas.
- **Dónde se guarda el índice de la documentación** (en memoria al arrancar o en un volumen).

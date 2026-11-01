# messaging-lab

Laboratorio visual para aprender, experimentar y comparar sistemas de mensajería con RabbitMQ y Apache Kafka.

## Qué incluye

| Sección | Qué puedes hacer | Especificación |
|---|---|---|
| **Estado** | Ver si el backend, RabbitMQ (y su trazado) y el asistente de IA están en marcha, y parar o levantar RabbitMQ y el asistente | [01](specs/01-project-foundation/spec.md), [07](specs/07-decoupled-assistant/spec.md) |
| **Laboratorio RabbitMQ** | Crear exchanges, colas y bindings; publicar, inspeccionar y consumir mensajes decidiendo qué hacer con cada uno (confirmar, rechazar y reencolar, o rechazar a una dead-letter queue) | [02](specs/02-rabbitmq-integration/spec.md) |
| ↳ Guía | Aprender RabbitMQ desde cero, con tres ejemplos paso a paso | [03](specs/03-rabbitmq-module-upgrade/spec.md) |
| ↳ Generador | Publicar mensajes sin parar, como un sensor o un robot, y consumirlos con consumidores simulados | [04](specs/04-rabbitmq-traffic-generator/spec.md) |
| ↳ Flujo | Ver en tiempo real cómo viaja cada mensaje, con gráficas | [05](specs/05-flow-visualizer/spec.md) |
| **Asistente de IA** (icono de chat) | Preguntar en español por lo que pasa en el laboratorio o por cualquier concepto, con modelos locales | [06](specs/06-ai-assistant/spec.md), [07](specs/07-decoupled-assistant/spec.md) |
| **Laboratorio Kafka** | Todavía no disponible: aparece en el menú como «Disponible próximamente» | — |

## Inicio rápido

Solo necesitas Docker (con al menos 6 GB de memoria asignados). Desde la raíz del repositorio:

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

Abre <http://localhost:4200>. La primera vez tarda varios minutos, porque construye las imágenes y descarga unos 10 GB (la imagen de Ollama y los modelos de IA). Mientras tanto, el laboratorio ya funciona y el asistente aparece como «Preparándose». Si no quieres el asistente, arranca con `OLLAMA_REPLICAS=0` delante del comando: no descarga nada de IA.

Para pararlo todo: `docker compose -f infra/docker-compose.yml down`. El resto del README explica cada parte con detalle.

## Arquitectura

![Arquitectura de messaging-lab: el navegador habla con el frontend (nginx), que envía /api al backend y /api/assistant al asistente de IA; el backend habla con RabbitMQ y, para parar y levantar servicios, con un intermediario de Docker; el asistente solo lee del backend y usa los modelos locales de Ollama y la documentación del proyecto](docs/arquitectura.png)

- **Frontend** (Angular 21, servido por nginx): la interfaz. nginx envía `/api/assistant` al asistente y el resto de `/api` al backend.
- **Backend** (Java 21 y Spring Boot 4.1): el único que habla con RabbitMQ. Sus núcleos (generador, flujo, diagnóstico y control) no dependen del broker, para poder añadir Kafka sin tocarlos; RabbitMQ y Docker se usan a través de adaptadores.
- **Asistente de IA** (Python 3.12 y FastAPI): solo lee del backend (por GET) y usa los modelos locales de Ollama y la documentación del proyecto. Ver [Asistente de IA](#asistente-de-ia).
- **Control de contenedores:** el backend no tiene acceso a Docker; se lo pide a un intermediario que solo permite listar, arrancar y parar. Ver [Parar y levantar RabbitMQ y el asistente desde «Estado»](#parar-y-levantar-rabbitmq-y-el-asistente-desde-estado).

## Contenido del repositorio

| Carpeta | Contenido |
|---------|-----------|
| `backend/` | Aplicación Spring Boot (Java 21), pruebas y contrato OpenAPI (`backend/api/api.yml`) |
| `frontend/` | Aplicación Angular 21 y sus pruebas |
| `assistant/` | Asistente de IA (Python 3.12 y FastAPI), su contrato (`assistant/api/api.yml`), pruebas y evaluación |
| `infra/` | Docker Compose (RabbitMQ, backend, frontend, asistente, Ollama e intermediario de Docker) y plantilla de configuración local |
| `specs/` | Especificaciones, planes y tareas por funcionalidad |
| `docs/` | Constitución y documentación general del proyecto |

## Requisitos previos

| Herramienta | Versión | Para qué |
|-------------|---------|----------|
| JDK | 21 | Backend en local y sus pruebas |
| Node.js | 20.19, 22.12 o 24 y superiores (probado con 24) | Frontend en local y sus pruebas |
| uv | 0.13 o superior (probado con 0.13.6) | Asistente de IA en local y sus pruebas (descarga Python 3.12 solo) |
| Docker con Compose v2 | Docker 29 / Compose 5 (probado) | RabbitMQ, arranque completo con un único comando y pruebas de integración |
| Google Chrome | Última versión estable | Navegador en el que se verifica la interfaz |

No hace falta instalar Maven: el proyecto incluye Maven Wrapper (`./mvnw`).

Para el arranque local por componentes necesitas JDK, Node.js y Docker (para ejecutar RabbitMQ); para el arranque completo solo necesitas Docker.

## Puertos y configuración

| Componente | Puerto por defecto |
|------------|--------------------|
| Frontend (interfaz web) | `4200` |
| Backend (API) | `8080` |
| RabbitMQ (AMQP) | `5672` |
| Consola de administración de RabbitMQ | `15672` |
| Ollama (modelos de IA) | `11434` |
| Asistente de IA (solo en el arranque local por componentes; en Docker va por el frontend) | `8000` |

No hace falta configurar nada para usar los valores por defecto. Para cambiarlos **sin modificar ningún archivo versionado**:

1. Copia la plantilla: `cp infra/.env.example infra/.env`
2. Edita `infra/.env` y descomenta las variables que quieras cambiar:

   ```
   BACKEND_PORT=8081
   FRONTEND_PORT=4300
   RABBITMQ_PORT=5673
   RABBITMQ_MANAGEMENT_PORT=15673
   OLLAMA_PORT=11435
   RABBITMQ_USER=guest
   RABBITMQ_PASSWORD=guest
   HEARTBEAT_INTERVAL=15s
   ```

3. Reinicia los componentes. Con Docker: `docker compose -f infra/docker-compose.yml up -d`.

`infra/.env.example` es solo la plantilla, con todas las variables comentadas y sus valores por defecto: no la edites para configurar tu equipo. En `infra/.env` pon solo lo que quieras cambiar; lo que no aparezca usa el valor por defecto. Las variables del asistente de IA (modelo, memoria, Ollama instalado fuera de Docker) se explican en [Asistente de IA](#asistente-de-ia).

`infra/.env` está ignorado por git. Es un único archivo que leen el backend, el frontend y Docker Compose, así que el cambio se aplica a todos y la interfaz sigue encontrando el backend.

Ten en cuenta:

- **El backend y el frontend deben leer el mismo puerto.** Si cambias el puerto del backend de otra forma (por ejemplo, con `--server.port` al arrancarlo), la interfaz seguirá buscándolo en el puerto de `infra/.env` y mostrará «El backend no está disponible.». Cambia siempre `BACKEND_PORT` en `infra/.env`.
- **`npm start` es el que carga `infra/.env`.** Si ejecutas `ng serve` directamente, se usan los puertos por defecto.
- **Una variable definida en tu terminal tiene prioridad** sobre la de `infra/.env`.
- **En el arranque completo**, `BACKEND_PORT`, `FRONTEND_PORT`, `RABBITMQ_PORT` y `RABBITMQ_MANAGEMENT_PORT` solo cambian los puertos publicados en tu máquina. La comunicación interna entre contenedores no cambia.
- **Los puertos de RabbitMQ solo son accesibles desde tu máquina** (`127.0.0.1`), no desde otros equipos de la red.
- **`HEARTBEAT_INTERVAL`** (por defecto `15s`) es cada cuánto envía el backend un latido por el flujo de eventos de los consumidores. Ver [Cerrar la pestaña con consumidores activos](#cerrar-la-pestaña-con-consumidores-activos).

### Credenciales de RabbitMQ

Por defecto se usan `guest` / `guest`, las credenciales públicas de RabbitMQ. **No son un secreto y están en el repositorio a propósito**: solo sirven para el desarrollo local, porque RabbitMQ únicamente es accesible desde tu máquina. No las uses en un entorno compartido ni expuesto a una red. Si quieres otras, define `RABBITMQ_USER` y `RABBITMQ_PASSWORD` en `infra/.env` (o en tu terminal) y reinicia; si ya tenías datos creados con otras credenciales, elimínalos antes (ver [Datos de RabbitMQ](#datos-de-rabbitmq)).

La consola de administración de RabbitMQ está en <http://localhost:15672> y entra con esas mismas credenciales. Sirve para contrastar lo que ves en el laboratorio.

## Arranque

Hay dos formas de arrancar la aplicación. Ambas muestran lo mismo.

### Opción 1: arranque completo con un único comando (Docker)

Desde la raíz del repositorio:

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

Levanta RabbitMQ, el backend, el frontend, el asistente de IA con su Ollama y el intermediario de Docker que permite parar y levantar servicios desde «Estado». La primera vez tarda varios minutos, porque construye las imágenes y descarga los modelos de IA. Después abre <http://localhost:4200>.

Para no levantar el asistente ni descargar nada de IA: `OLLAMA_REPLICAS=0 docker compose -f infra/docker-compose.yml up -d --build`.

Para ver los registros: `docker compose -f infra/docker-compose.yml logs -f`

Para detenerlo todo (conserva los datos de RabbitMQ y los modelos descargados):

```bash
docker compose -f infra/docker-compose.yml down
```

### Opción 2: arranque local por componentes

Es la forma cómoda para desarrollar: solo RabbitMQ (y, si quieres el asistente, Ollama) va en Docker. Necesitas tres terminales, o cuatro con el asistente, desde la raíz del repositorio.

**Terminal 1 — solo RabbitMQ:**

```bash
docker compose -f infra/docker-compose.yml up -d rabbitmq
```

El backend arranca aunque RabbitMQ aún no esté listo y se conecta cuando lo está.

**Terminal 2 — backend:**

```bash
cd backend && ./mvnw spring-boot:run
```

**Terminal 3 — frontend** (la primera vez, instala las dependencias):

```bash
cd frontend
npm ci
npm start
```

**Terminal 4 — asistente de IA (opcional):** necesita un Ollama con los modelos `qwen2.5:3b` y `bge-m3` (por ejemplo, `docker compose -f infra/docker-compose.yml up -d ollama ollama-model`, o un Ollama instalado en tu máquina):

```bash
cd assistant && uv run uvicorn app.main:app --port 8000
```

El proxy de desarrollo del frontend le envía `/api/assistant` (puerto `ASSISTANT_PORT`, 8000 por defecto). Sin él, el asistente se muestra como no disponible.

Después abre <http://localhost:4200>. Para detener el backend, el frontend y el asistente, pulsa `Ctrl+C` en su terminal; RabbitMQ se detiene con `docker compose -f infra/docker-compose.yml down`. En este modo no se pueden parar ni levantar servicios desde «Estado» (solo con el Docker Compose).

### Datos de RabbitMQ

Los exchanges, las colas, los bindings y los mensajes se guardan en un volumen de Docker y **sobreviven** a detener y volver a levantar RabbitMQ. Los modelos de IA y el índice de la documentación también se guardan en volúmenes.

| Quiero | Comando |
|--------|---------|
| Detener conservando los datos | `docker compose -f infra/docker-compose.yml down` |
| Empezar de cero (borra exchanges, colas, bindings y mensajes, **y también los modelos de IA**, que se vuelven a descargar: unos 3 GB) | `docker compose -f infra/docker-compose.yml down -v` |
| Borrar solo los datos de RabbitMQ | `docker compose -f infra/docker-compose.yml down` y después `docker volume rm messaging-lab_rabbitmq-data` |

## Comprobar que funciona

1. Abre <http://localhost:4200>. Debes ver la cabecera `messaging-lab`, el menú y, en la vista **Estado**: el backend **Disponible** (la aplicación `messaging-lab-backend` y la versión `0.1.0`) y, debajo, el **Estado de RabbitMQ**: si está **levantado** (con su versión) y si el **trazado** está activo (`tracing: true`) o desactivado, con cómo activarlo; y la tarjeta del **Asistente de IA** (Listo, Preparándose o No disponible). Con el Docker Compose, las tarjetas de RabbitMQ y del asistente tienen los botones **Parar** y **Levantar**. El botón «Volver a comprobar» repite la comprobación.
2. Comprueba la API directamente:

   ```bash
   curl http://localhost:8080/api/status
   ```

   Respuesta esperada: `{"status":"AVAILABLE","name":"messaging-lab-backend","version":"0.1.0"}`

3. Comprueba el comportamiento ante un fallo: detén el backend y recarga la página. Debe aparecer **«El backend no está disponible.»** con un botón **Reintentar**. Vuelve a arrancar el backend y pulsa el botón: la interfaz mostrará el estado sin recargar la página.

### La guía del Laboratorio RabbitMQ

La pestaña **Guía** está pensada para quien no conoce RabbitMQ y se lee sin necesidad de que RabbitMQ esté disponible. Tiene un submenú con cuatro partes: **Cómo funciona** (qué es cada pieza, los tipos de exchange, glosario y qué hacer si algo falla), **Ejemplo 1: la pizzería** (paso a paso, a mano), **Ejemplo 2: la variante** (un aviso para todos con un exchange fanout) y **Ejemplo 3: con el generador** (mensajes continuos, por un exchange y directamente a una cola, vistos en tiempo real desde la pestaña Flujo). La guía recuerda la parte que estabas leyendo al ir a otra pestaña y volver.

### Escenario guiado del Laboratorio RabbitMQ

Con todo en marcha, entra en **Laboratorio RabbitMQ** en el menú. Debe poner **Conectado**. Sigue estos pasos:

1. **Exchanges:** crea un exchange de tipo `topic` llamado `eventos`.
2. **Colas:** crea una cola `fallidos`. Después crea una cola `pedidos` eligiendo `fallidos` como dead-letter queue.
3. **Bindings:** crea un binding de `eventos` a `pedidos` con el patrón `pedido.*`.
4. **Publicación:** publica en `eventos` con la routing key `pedido.creado` y el cuerpo que quieras. Debe decir que se ha enrutado. Publica otro con `otra.cosa`: debe decir que no se ha podido enrutar y se ha descartado.
5. **Colas:** pulsa **Actualizar** y comprueba que `pedidos` tiene 1 mensaje listo. Pulsa **Inspeccionar** para ver su contenido sin consumirlo.
6. **Consumidores:** inicia un consumidor en modo **manual** sobre `pedidos`. Recibirá el mensaje. Pulsa **Rechazar sin reencolar**: el mensaje pasa a `fallidos`.
7. **Colas:** inspecciona `fallidos`. Verás el mensaje con su cola de origen (`pedidos`) y el motivo (`rejected`).

Para ver el reparto entre consumidores, inicia dos sobre la misma cola y publica varios mensajes. Al salir del laboratorio o cerrar la pestaña, los consumidores se detienen y los mensajes que tuvieran pendientes de confirmar vuelven a la cola.

**Limitaciones que conviene conocer:**

- La inspección no retira los mensajes, pero RabbitMQ no ofrece una lectura sin efecto: puede dejarlos marcados como **reentregados**. La interfaz lo avisa.
- Al cerrar o recargar la pestaña, el servidor tarda hasta unos 30 segundos en darse cuenta y devolver a la cola los mensajes pendientes (ver [más abajo](#cerrar-la-pestaña-con-consumidores-activos), donde se explica cómo reducirlo). Cambiar de área dentro del laboratorio no detiene los consumidores.
- **Ciclos de dead-letter queues:** si varias colas se configuran como dead-letter queue unas de otras formando un ciclo, RabbitMQ **descarta los mensajes en silencio**. El laboratorio solo impide que una cola sea su propia dead-letter queue; los ciclos más largos no se detectan.

El contrato de la API está en [`backend/api/api.yml`](backend/api/api.yml).

#### Cerrar la pestaña con consumidores activos

Los consumidores viven mientras la pestaña mantiene abierto su flujo de eventos. Cuando la pestaña se cierra o se recarga, el navegador no avisa al servidor: este solo se da cuenta cuando falla el siguiente **latido** que envía por ese flujo (uno cada 15 segundos por defecto), normalmente al segundo. Hasta entonces, un mensaje que el consumidor tenía pendiente de confirmar sigue sin verse en la cola; después vuelve a ella marcado como reentregado. Eso son **hasta unos 30 segundos** con el valor por defecto.

Si prefieres que se note menos, reduce el intervalo con `HEARTBEAT_INTERVAL`:

- **Arranque completo (Docker) o backend en local:** descomenta `HEARTBEAT_INTERVAL` en `infra/.env` (por ejemplo, `HEARTBEAT_INTERVAL=5s`) y reinicia el backend. En Docker, `docker compose -f infra/docker-compose.yml up -d` vuelve a crear el contenedor con el valor nuevo.
- **Una sola vez, desde la terminal:** `HEARTBEAT_INTERVAL=5s ./mvnw spring-boot:run`.

Un intervalo menor reduce esa espera, a costa de enviar más latidos. Con `5s`, la espera pasa a ser de unos 10 segundos como máximo.

### Generador de tráfico

La pestaña **Generador** del Laboratorio RabbitMQ publica mensajes sin parar, como lo haría un robot o un sensor, para ver cómo se comportan las colas con carga continua. Los emisores corren en el **backend**: siguen funcionando aunque cambies de pestaña o cierres el navegador, hasta que los pares o reinicies el backend (al reiniciar no queda nada en marcha).

1. En **Nuevo emisor**, elige una plantilla del catálogo (robot UR, temperatura, sonómetro, vibración, tag de PLC, alarma) o escribe la tuya, y pulsa **Vista previa** para ver un mensaje de ejemplo.
2. Elige el destino (un exchange con su routing key, o directamente una cola) y la frecuencia: **fija** (cada N ms) o **aleatoria dentro de un rango**.
3. Pulsa **Crear emisor** y luego **Iniciar**. Puedes pausarlo, pararlo o eliminarlo. **Parar todo** detiene emisores y consumidores simulados.
4. Para que una cola se vacíe, crea un **consumidor simulado** (tiempo de proceso, porcentaje de fallos y si el mensaje fallido se devuelve a la cola o se rechaza hacia la dead-letter queue). Un emisor más rápido que su consumidor hace crecer la cola, y se ve en **Colas**, que se actualiza sola cada 2 s.

**Plantillas.** Son texto con funciones entre `{{ }}`: `numero`, `deriva`, `senoidal`, `contador`, `ahora`, `elegir`, `booleano` y `ejes`. No se ejecuta código; una plantilla inválida se rechaza indicando línea y columna.

**Límites de seguridad.** La suma de los emisores en marcha no puede superar un límite global (100 mensajes por segundo por defecto, editable en la pantalla hasta un máximo absoluto de 500). Hay un máximo de 20 emisores y 20 consumidores simulados. Cuando una cola supera los 10 000 mensajes listos, la pantalla avisa.

**Dos tipos de consumidor.** Los *simulados* (Generador) consumen solos en el servidor. Los *de sesión* (Consumidores) los controlas tú mensaje a mensaje y se detienen al cerrar la pestaña.

Contrato: operaciones `/api/rabbitmq/generator/*` en [`backend/api/api.yml`](backend/api/api.yml). Especificación: [`specs/04-rabbitmq-traffic-generator/`](specs/04-rabbitmq-traffic-generator/).

### Flujo en tiempo real (visualizador)

La pestaña **Flujo** del Laboratorio RabbitMQ muestra, con datos reales, cómo viaja cada mensaje: quién publica, por qué exchange pasa, a qué colas llega y qué consumidor lo recibe. Es la forma de *ver* lo que el generador produce a mucha velocidad.

- **Diagrama animado:** publicadores → exchanges → colas → consumidores, con el ritmo de cada uno y los mensajes listos de cada cola. Solo se dibuja lo que tiene **flujo activo** (algún mensaje en los últimos 10 segundos): lo que se queda quieto desaparece y vuelve a salir cuando pasa un mensaje por él. Un punto recorre el camino de cada mensaje; una publicación que no llega a ninguna cola se dibuja como un cuadrado que se desvanece en el exchange. Se puede **pausar** y **ralentizar** (hasta ÷10) para seguir un mensaje con calma, y si tu sistema pide menos movimiento no hay animación (se sigue en el registro).
- **Registro de eventos:** los últimos eventos con su recorrido, routing key y tamaño; al abrir un evento se ve el cuerpo y las cabeceras. Se puede pausar y limpiar.
- **Filtro:** por nombre de exchange o cola, o por routing key, con los comodines de un topic (`*` una palabra, `#` varias).
- **Gráficas:** mensajes por segundo (publicados y entregados) y mensajes listos de las colas elegidas, de los últimos 2 minutos. Con un emisor más rápido que su consumidor se ve crecer la cola.
- Con mucho tráfico se animan y listan como máximo 50 eventos por segundo (lo omitido se indica); los contadores y las gráficas son exactos.

**Qué es el trazado.** Los datos salen del *trazado* (*firehose*) de RabbitMQ: cuando está activo, RabbitMQ copia cada publicación y cada entrega a un exchange interno (`amq.rabbitmq.trace`), y el backend lo escucha solo mientras haya una pantalla de Flujo abierta. Por eso se ve también el tráfico de otras aplicaciones que usen el mismo RabbitMQ. Ten en cuenta que **duplica cada mensaje dentro de RabbitMQ**: es útil en un laboratorio, no en producción.

**Activarlo y desactivarlo.**

- Con el Docker Compose del proyecto, RabbitMQ arranca con el trazado activado y lo mantiene (también tras reiniciarlo). Si ya tenías el contenedor creado antes de añadirse esto, recréalo con `docker compose -f infra/docker-compose.yml up -d`: los datos se conservan porque el volumen es el mismo.
- Con otro RabbitMQ, o para activarlo a mano: `rabbitmqctl trace_on` (con Docker: `docker compose -f infra/docker-compose.yml exec rabbitmq rabbitmqctl trace_on`). Se pierde al reiniciar RabbitMQ.
- Para desactivarlo: `docker compose -f infra/docker-compose.yml exec rabbitmq rabbitmqctl trace_off`. Aviso: el Compose lo vuelve a activar a los pocos segundos; para quitarlo de forma permanente, usa otro arranque de RabbitMQ.
- Si el trazado está desactivado, la pantalla lo dice y muestra el comando; en cuanto se activa, empieza a mostrar datos sin recargar.

**Limitaciones.** El trazado solo registra publicaciones y entregas: no muestra confirmaciones, rechazos ni reencolados individuales (su efecto se ve en la gráfica de mensajes listos). Las conexiones nuevas tardan unos segundos en mostrar el nombre que puso su cliente; mientras tanto se ve el de RabbitMQ (dirección de origen y destino).

**Reutilizarlo en otro laboratorio.** El visualizador está hecho para usarse tal cual en otros laboratorios (por ejemplo, Kafka). Lo compartido está en `frontend/src/app/shared/flow` (modelo, diagrama, registro, gráficas y `FlowView`) y en el paquete `com.messaginglab.flow` del backend, sin ninguna referencia a RabbitMQ. Un laboratorio nuevo solo aporta una fuente de datos que implemente `FlowSource` (frontend) y los puertos `FlowSource` y `FlowTopologyProvider` (backend), más una pestaña «Flujo» con `<app-flow-view>`. Contrato: operaciones `/api/rabbitmq/flow/*` en [`backend/api/api.yml`](backend/api/api.yml). Especificación: [`specs/05-flow-visualizer/`](specs/05-flow-visualizer/).

### Asistente de IA

El icono de chat a la derecha de la cabecera abre un asistente que responde en español a preguntas sobre el laboratorio: qué es cada pieza, por qué crece una cola, si el trazado está activo… Usa **modelos de IA locales** servidos por Ollama: sin claves, sin coste y sin que salga ningún dato de tu máquina. Es de **solo lectura**: si le pides una acción, te dice dónde hacerla.

**Cómo funciona.** Es un servicio aparte (`assistant/`, Python y FastAPI) que solo consulta el backend por GET:

```
navegador ─► frontend (nginx) ─/api/assistant─► assistant ─► ollama (chat y búsqueda)
                  └─/api/*────────► backend ◄─── solo GET ───┘
```

- El backend calcula por reglas las conclusiones (por ejemplo, «la cola cocina crece porque entran 10 y salen 5») y la actividad; el asistente se las pasa al modelo junto con los datos que tocan para la pregunta, y el modelo puede pedir más con sus herramientas de solo lectura (como mucho 4 por respuesta).
- Algunas preguntas se responden **sin el modelo**, con el texto exacto y en menos de 2 segundos, porque un modelo pequeño las mezcla o se inventa datos: el estado general («¿está todo bien?»), la actividad («¿hay algo pasando datos?»), cualquier pregunta con RabbitMQ caído, las peticiones de cambio («borra la cola…»: dice que no puede y dónde se hace), cómo cambiar puertos o el modelo, cómo reiniciar servicios y qué apartados tiene la aplicación.
- Para los conceptos busca **por significado** en la guía, el README y las especificaciones, con el modelo de embeddings `bge-m3`. El índice se calcula la primera vez (alrededor de un minuto) y se guarda en un volumen; solo se recalcula si la documentación cambia.

**De dónde saca lo que sabe** (por si quieres corregirlo o ampliarlo):

| Qué | Dónde |
|---|---|
| El manual: fichas de conceptos, pestañas, tecnologías, puertos, modelos… | `assistant/app/knowledge.py` (lista `CARDS`) |
| La documentación en la que busca | `README.md`, `specs/*/spec.md` (sin las secciones de historia, como «Contexto y objetivo») y el texto de la guía (`frontend/src/app/features/rabbitmq/guide/guide-content.ts`) |
| Las instrucciones del modelo | `INSTRUCTIONS` en `assistant/app/service.py` |
| Las conclusiones sobre el laboratorio | El backend, con reglas: `backend/src/main/java/com/messaginglab/diagnostics/` |

Después de cambiar algo del asistente, reconstrúyelo: `docker compose -f infra/docker-compose.yml up -d --build assistant`.

**Cambiar el modelo.** Pon `ASSISTANT_MODEL` en `infra/.env` y vuelve a levantar con `docker compose -f infra/docker-compose.yml up -d --build`; la primera vez se descarga. Perfiles:

| Perfil | Modelo | Descarga | Memoria de Ollama (con `bge-m3`) | Memoria para Docker | Acierto en la evaluación | Primera palabra (CPU) |
|---|---|---|---|---|---|---|
| **Por defecto** | `qwen2.5:3b` | ~2 GB + 1,2 GB | ~3,9 GB (límite `OLLAMA_MEMORY_LIMIT=4g`) | 6 GB o más | 94 % | ~3 s (máx. ~6 s) |
| Grande (opcional) | `qwen2.5:7b` | ~4,7 GB + 1,2 GB | ~5,7 GB (`OLLAMA_MEMORY_LIMIT=7g`) | 10 GB o más | 97 % | ~6 s (máx. ~30 s) |
| Ligero (sin evaluar) | `qwen2.5:1.5b` | ~1 GB + 1,2 GB | menos de 4 GB | 6 GB | responde peor | más rápido |

La primera vez también se descarga la imagen de Ollama (unos 7 GB). Mientras se descargan los modelos o se indexa la documentación, el asistente y «Estado» dicen que se está preparando. Tras 5 minutos sin preguntas, los modelos se descargan de la memoria.

Las respuestas largas pueden tardar varias decenas de segundos. La longitud máxima es de 700 tokens (`ASSISTANT_MAX_RESPONSE_TOKENS`); si una respuesta llega a ese límite, el asistente avisa de que se ha cortado.

**Velocidad según el sistema.** Dentro de Docker, Ollama usa **solo la CPU**. Los tiempos de la tabla se han medido en un Mac con chip Apple y 10 CPU para Docker:

- **macOS:** Docker no puede usar la GPU. Para ir varias veces más rápido, instala Ollama en tu Mac (usa la GPU de Apple) y apúntale el asistente: en `infra/.env`, `OLLAMA_REPLICAS=0`, `ASSISTANT_REPLICAS=1` y `ASSISTANT_OLLAMA_URL=http://host.docker.internal:11434`; descarga tú los modelos con `ollama pull qwen2.5:3b` y `ollama pull bge-m3`. En un Mac de 8 GB, el perfil grande dentro de Docker no cabe: usa el Ollama nativo.
- **Linux:** con CPU, tiempos parecidos a los de la tabla según los núcleos. Con una GPU NVIDIA, la opción más rápida es también un Ollama nativo.
- **Windows (Docker Desktop con WSL 2):** solo CPU, como en macOS; da a WSL la memoria de la tabla. Un Ollama nativo de Windows también sirve con `ASSISTANT_OLLAMA_URL=http://host.docker.internal:11434`.

Tu configuración de Ollama nativo vive en tu `infra/.env`, que no se sube al repositorio: quien clone el proyecto sin `infra/.env` lo levanta todo dentro de Docker sin configurar nada.

**No levantarlo:** pon `OLLAMA_REPLICAS=0` en `infra/.env` o delante del comando (`OLLAMA_REPLICAS=0 docker compose -f infra/docker-compose.yml up -d --build`). Todo lo demás funciona igual y el asistente sale como no disponible.

**Evaluación.** Un banco fijo de 44 preguntas (`assistant/evaluation/questions.json`) mide el acierto y la latencia. Tiene 8 escenarios: cola que crece, cola sin consumidor, dead-letter queue, mensajes sin enrutar, trazado apagado, emisor fallado, RabbitMQ caído y sin nada en marcha; en este último hay también preguntas de conceptos, del proyecto y ajenas. Monta y borra cosas en el laboratorio y para RabbitMQ, así que úsalo con un stack aparte (en otro proyecto y con otros puertos):

```bash
BACKEND_PORT=18080 FRONTEND_PORT=14200 RABBITMQ_PORT=15673 RABBITMQ_MANAGEMENT_PORT=15674 OLLAMA_PORT=11435 \
  docker compose -p ml-eval -f infra/docker-compose.yml up -d --build
cd assistant && EVAL_URL=http://localhost:14200 EVAL_RABBITMQ_CONTAINER=ml-eval-rabbitmq-1 \
  uv run pytest -m evaluation tests/test_evaluation.py -s
```

El informe queda en `assistant/evaluation/last-report.md`. Al terminar: `docker compose -p ml-eval -f infra/docker-compose.yml down -v`.

### Parar y levantar RabbitMQ y el asistente desde «Estado»

Con el Docker Compose, las tarjetas de RabbitMQ y del asistente de «Estado» tienen los botones **Parar** y **Levantar** (parar pide confirmación y explica las consecuencias). Parar el asistente también para Ollama y libera la memoria del modelo. Al levantar RabbitMQ, el resto del laboratorio se recupera solo.

El backend no tiene acceso a Docker: se lo pide a un intermediario (`docker-proxy`, imagen `tecnativa/docker-socket-proxy`) que **solo deja listar, arrancar y parar contenedores** ([`infra/docker-proxy/haproxy.cfg.template`](infra/docker-proxy/haproxy.cfg.template)), no publica ningún puerto y solo es visible para el backend. Además, el backend solo actúa sobre RabbitMQ y el asistente de **este** proyecto Compose. Aun así, el intermediario ve el socket de Docker de la máquina: es una función pensada para un equipo de desarrollo local.

Sin el Docker Compose (arranque por componentes), los botones no aparecen y la tarjeta explica qué comando usar.

Especificaciones: [`specs/06-ai-assistant/`](specs/06-ai-assistant/) y [`specs/07-decoupled-assistant/`](specs/07-decoupled-assistant/).

## Pruebas

Las pruebas de cada componente se ejecutan por separado y no necesitan que el otro esté en marcha.

**Backend** (no necesita Docker ni RabbitMQ):

```bash
cd backend && ./mvnw test
```

**Backend, con pruebas de integración contra un RabbitMQ real:**

```bash
cd backend && ./mvnw verify
```

Necesitan **Docker en marcha**: arrancan un RabbitMQ desechable con Testcontainers (la primera vez descargan la imagen `rabbitmq:4.4-management`) y no usan el que tengas levantado ni tus datos. Tardan unos minutos.

**Frontend** (no necesita backend ni RabbitMQ):

```bash
cd frontend && npm test
```

En una terminal interactiva, `npm test` se queda vigilando los cambios. Para una sola ejecución que termine:

```bash
cd frontend && npm test -- --watch=false
```

**Asistente de IA** (no necesita backend, Ollama ni RabbitMQ; usa dobles de prueba). La primera vez, uv descarga Python 3.12 y las dependencias:

```bash
cd assistant && uv run pytest
```

La evaluación contra el stack real y la prueba de búsqueda con embeddings reales no se ejecutan por defecto (marca `evaluation`, ver «Asistente de IA»).

## Problemas frecuentes

- **El arranque falla porque un puerto está ocupado.** Elige otros puertos en `infra/.env` (ver [Puertos y configuración](#puertos-y-configuración)). Sirve para el frontend, el backend, RabbitMQ y su consola. Para saber quién usa un puerto: `lsof -iTCP:5672 -sTCP:LISTEN`.
- **RabbitMQ no arranca.** Mira sus registros: `docker compose -f infra/docker-compose.yml logs rabbitmq`. Las causas habituales son un puerto ocupado, Docker sin memoria suficiente o datos antiguos incompatibles: en ese último caso puedes empezar de cero con `down -v` (borra los datos).
- **El laboratorio muestra «RabbitMQ no está disponible.»** RabbitMQ está detenido o aún arrancando. Comprueba que está en marcha (`docker compose -f infra/docker-compose.yml ps`) y pulsa **Reintentar**: no hace falta recargar la página ni reiniciar el backend.
- **El laboratorio dice que RabbitMQ ha rechazado las credenciales.** Las credenciales que usa el backend no coinciden con las de los datos de RabbitMQ. Usa las mismas en `infra/.env` o elimina los datos con `down -v`.
- **La interfaz muestra «El backend no está disponible.»** El backend no ha terminado de arrancar o está detenido, o el frontend y el backend usan puertos distintos. Espera unos segundos y pulsa **Reintentar**. Si persiste, revisa que `BACKEND_PORT` sea el correcto.
- **Los consumidores no reciben mensajes en el arranque completo.** Reconstruye con `up -d --build`: la configuración de nginx que deja pasar el flujo de eventos se aplica al construir la imagen del frontend.
- **Cambio el código y en Docker no se nota.** Usa siempre `up -d --build`; sin `--build` Docker reutiliza las imágenes anteriores.
- **`./mvnw verify` falla enseguida.** Comprueba que Docker está en marcha (`docker info`).
- **El asistente lleva mucho rato «Preparándose».** La primera vez descarga unos 3 GB de modelos; mira el progreso con `docker compose -f infra/docker-compose.yml logs -f ollama-model`. Después indexa la documentación, alrededor de un minuto.
- **El asistente sale «No disponible».** Comprueba que los contenedores `assistant` y `ollama` están en marcha (`docker compose -f infra/docker-compose.yml ps`), o levántalo desde su tarjeta en «Estado». Si arrancaste con `OLLAMA_REPLICAS=0`, no se puede usar.
- **El asistente tarda mucho.** La primera pregunta tras 5 minutos sin usarlo carga los modelos en memoria. Dentro de Docker solo usa la CPU: da más CPU a Docker, usa un modelo más ligero o, en un Mac, instala Ollama fuera de Docker (ver [Asistente de IA](#asistente-de-ia)).
- **El asistente responde algo que no cuadra.** Es un modelo pequeño y puede equivocarse. Las conclusiones sobre colas, emisores y RabbitMQ las calcula el backend con reglas y son fiables; compruébalo en la pestaña correspondiente, o pregunta de forma más concreta.

## Documentación del proyecto

- [`docs/constitution.md`](docs/constitution.md): principios del proyecto.
- [`specs/`](specs/): especificaciones, planes y tareas de cada funcionalidad.
- [`AGENTS.md`](AGENTS.md): instrucciones para los agentes de IA.

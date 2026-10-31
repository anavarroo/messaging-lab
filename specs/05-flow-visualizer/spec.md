# Especificación 05 — Visualizador de flujo del Laboratorio RabbitMQ

- **Estado:** Aprobada
- **Fecha:** 2026-10-30

## 1. Contexto y objetivo

En el [Laboratorio RabbitMQ](../02-rabbitmq-integration/spec.md) un mensaje publicado a mano se enruta y se entrega en milisegundos: no da tiempo a verlo. El [generador de tráfico](../04-rabbitmq-traffic-generator/spec.md) ya produce flujo continuo, pero solo se ve en contadores.

**Objetivo:** una pestaña más del Laboratorio RabbitMQ, **«Flujo»**, que muestre **en tiempo real y con datos reales** cómo viaja cada mensaje: quién publica, por qué exchange, a qué colas llega y qué consumidor lo recibe. Incluye un diagrama animado, un registro de eventos y gráficas de ritmo y tamaño de las colas.

**Datos reales, no simulados:** el visualizador se alimenta del **trazado nativo de RabbitMQ** (*firehose*), que copia cada publicación y cada entrega a un exchange especial. Por eso ve también el tráfico de aplicaciones externas que usen el mismo RabbitMQ, no solo el del laboratorio.

**Un visualizador por laboratorio:** cada laboratorio tendrá su propia pestaña «Flujo», con los datos de su sistema, que solo se muestra cuando ese sistema está activo. Esta especificación cubre la de RabbitMQ. El diagrama, el registro y las gráficas serán componentes compartidos y el modelo de eventos de flujo no dependerá de RabbitMQ, para que la pestaña «Flujo» del Laboratorio Kafka los reutilice y solo aporte su propia fuente de datos.

## 2. Usuarios

- **Persona que aprende RabbitMQ**, que quiere *ver* el recorrido de los mensajes que genera con el laboratorio y el generador.
- **Programador de integración industrial** que quiere observar el tráfico de robots y sensores que pasa por las colas de RabbitMQ.

## 3. Requisitos

### RF-1 — Pestaña «Flujo» del laboratorio
- **CA-1.1:** El Laboratorio RabbitMQ tendrá una pestaña más, «Flujo», con su propia ruta, que respeta las reglas del laboratorio (pestañas fijas, posición recordada al cambiar de pestaña).
- **CA-1.2:** La entrada «Visualizador de flujo» del menú lateral se eliminará: el visualizador vive dentro de cada laboratorio.
- **CA-1.3:** La pantalla indicará en cada momento el estado de la fuente de datos de RabbitMQ: **no disponible** (RabbitMQ caído), **trazado desactivado** o **activo**.

### RF-2 — Activación y detección del trazado
- **CA-2.1:** El Docker Compose del proyecto dejará RabbitMQ con el trazado activado en el virtual host del laboratorio, también tras reiniciarlo.
- **CA-2.2:** La aplicación detectará si el trazado está activo. Si no lo está, la pantalla lo dirá en español y mostrará el comando exacto para activarlo, sin mostrar datos falsos.
- **CA-2.3:** Al activarse el trazado mientras la pantalla está abierta, o al volver RabbitMQ tras una caída, la pantalla empezará a mostrar datos sin recargar.
- **CA-2.4:** El tráfico del propio visualizador (su suscripción al trazado) **no** aparecerá en el flujo.
- **CA-2.6:** La pantalla «Estado» de la aplicación comprobará y mostrará si **RabbitMQ está levantado** (con su versión) y si tiene el **trazado activo** (`tracing: true`) o **desactivado** (`tracing: false`, con cómo activarlo). Si RabbitMQ no está disponible, lo dirá y no afirmará nada del trazado. Tendrá un botón «Volver a comprobar».
- **CA-2.5:** El README explicará qué es el trazado, que tiene un coste de rendimiento en RabbitMQ pensado para laboratorio y no para producción, y cómo activarlo y desactivarlo a mano.

### RF-3 — Eventos de flujo en tiempo real
- **CA-3.1:** El sistema recibirá de RabbitMQ y mostrará dos tipos de eventos: **publicación** (un mensaje entra en un exchange, con su routing key) y **entrega** (un mensaje sale de una cola hacia un consumidor).
- **CA-3.2:** De cada evento se mostrará: hora, tipo, exchange o cola, routing key, tamaño del cuerpo, y si es una reentrega.
- **CA-3.3:** El detalle de un evento permitirá ver el cuerpo (truncado a 4 KB, escapado para que nunca se interprete como HTML), las propiedades y las cabeceras.
- **CA-3.4:** De cada publicación se mostrarán las **colas a las que RabbitMQ la ha enrutado** (dato que incluye el propio trazado). Una publicación que no llega a ninguna cola se marcará como **sin enrutar**.
- **CA-3.5:** Los datos llegarán al navegador por un flujo en tiempo real; la pantalla no hará sondeo para los eventos.
- **CA-3.6:** Con mucho tráfico, el contador de eventos será exacto, pero la pantalla animará y listará como máximo **50 eventos por segundo**; lo que se omita se indicará («se omiten N eventos por segundo»).

### RF-4 — Diagrama animado
- **CA-4.1:** El diagrama mostrará de izquierda a derecha **publicadores → exchanges → colas → consumidores**, construido con la topología real de RabbitMQ (exchanges, bindings y colas) y con los consumidores conectados observados en el trazado.
- **CA-4.1b:** El diagrama dibujará **solo los elementos con flujo de datos activo**: los que han tenido algún mensaje en los últimos 10 segundos. Un elemento sin actividad desaparece del diagrama (y vuelve a aparecer cuando pasa un mensaje por él), igual que sus conexiones. Si no hay ninguno, la pantalla lo indicará. Tener mensajes listos en una cola no cuenta como flujo.
- **CA-4.2:** Cada evento se animará como un punto que recorre el camino: del publicador al exchange, del exchange a cada cola de destino y de la cola al consumidor.
- **CA-4.3:** Cada exchange y cada cola mostrará su ritmo actual en mensajes por segundo; cada cola, además, cuántos mensajes tiene listos.
- **CA-4.4:** Los mensajes sin enrutar se animarán de forma distinta (se desvanecen en el exchange) y quedarán contados.
- **CA-4.5:** El diagrama se actualizará cuando se creen o eliminen exchanges, colas o bindings.
- **CA-4.6:** El usuario podrá **pausar** y reanudar la animación, y **ralentizarla** (por ejemplo, a una décima parte de la velocidad) para poder seguir un mensaje con calma. Al pausar, los eventos se siguen contando y registrando.
- **CA-4.7:** Si el usuario tiene activada la preferencia de movimiento reducido del sistema, no habrá animación de recorrido; los eventos se verán en el registro y los contadores.
- **CA-4.8:** Se podrá **filtrar** por exchange, cola o routing key (con comodines como los de un topic), y el diagrama y el registro mostrarán solo lo que coincida.

### RF-5 — Registro de eventos
- **CA-5.1:** Un registro mostrará los últimos eventos recibidos, el más reciente arriba, con las columnas de CA-3.2.
- **CA-5.2:** El backend conservará en memoria los últimos **1000** eventos, de modo que al abrir la pantalla se ven los recientes y no empieza vacía.
- **CA-5.3:** El registro podrá pausarse para leerlo; al reanudarlo se verá lo ocurrido entretanto.
- **CA-5.4:** El usuario podrá limpiar el registro.

### RF-6 — Gráficas
- **CA-6.1:** Habrá una gráfica de **mensajes por segundo** publicados y entregados, de los últimos 2 minutos, con una serie por exchange o cola seleccionada.
- **CA-6.2:** Habrá una gráfica del **número de mensajes listos** de las colas seleccionadas en los últimos 2 minutos, para ver cómo crece o se vacía una cola cuando un emisor del generador es más rápido que su consumidor.
- **CA-6.3:** Las gráficas se actualizarán cada segundo, tendrán título, ejes con unidades, leyenda y una tabla accesible equivalente, y serán legibles con el tema de la aplicación (que hoy solo es claro); los colores son variables, para poder añadir un tema oscuro sin tocar los componentes.

### RF-7 — Reglas de la pantalla
- **CA-7.1:** Mensajes en español y estados claros: cargando, sin datos todavía («aún no ha pasado ningún mensaje»), error y RabbitMQ no disponible.
- **CA-7.2:** Si se corta la conexión de datos, la pantalla lo indicará, reintentará sola y conservará lo que ya mostraba.
- **CA-7.3:** El backend solo se suscribirá al trazado mientras haya al menos una pantalla del visualizador abierta, y dejará de hacerlo al cerrarse la última.
- **CA-7.4:** Al cambiar de pestaña y volver, conservará la posición de la pantalla, como el resto del laboratorio.

## 4. Requisitos no funcionales

- **RNF-1 (Rendimiento):** con el generador a su máximo (500 mensajes por segundo), la pantalla seguirá respondiendo y el backend no acumulará memoria sin límite (buffers acotados).
- **RNF-2 (Latencia):** un mensaje publicado se verá en la pantalla en menos de 2 segundos en condiciones normales.
- **RNF-3 (Seguridad):** los cuerpos y cabeceras se tratan como texto no fiable. No se guardan fuera de la memoria del backend ni se persisten.
- **RNF-4 (Accesibilidad):** el diagrama tendrá una alternativa textual (el registro y las tablas de las gráficas), no dependerá solo del color, y respetará el movimiento reducido (CA-4.7).
- **RNF-5 (Arquitectura):** el modelo de eventos de flujo y su procesamiento no dependerán de RabbitMQ ni de Angular; RabbitMQ será un adaptador, como en el resto del proyecto.
- **RNF-6 (Reutilización):** el visualizador será un componente genérico —núcleo del backend, contrato, componentes del frontend y pantalla— que el Laboratorio Kafka podrá usar aportando solo su fuente de datos y su pestaña, sin modificar lo compartido.

## 5. Fuera de alcance

- Kafka: su pestaña «Flujo» llegará con su laboratorio; aquí solo se deja preparado el modelo de eventos y los componentes compartidos.
- Una vista que compare RabbitMQ y Kafka a la vez.
- Confirmaciones (*ack*), rechazos y reencolados individuales: el trazado de RabbitMQ no los expone. Su efecto se ve en las gráficas de mensajes listos.
- Seguir un mensaje concreto de extremo a extremo con un identificador propio.
- Guardar o exportar el historial de eventos, o reproducir sesiones grabadas.
- Latencias entre publicación y entrega por mensaje.
- Otros virtual hosts distintos del configurado.

## 6. Criterios de finalización

1. El Laboratorio RabbitMQ tiene la pestaña «Flujo» y el menú lateral ya no tiene «Visualizador de flujo».
2. Con el Compose del proyecto, el trazado está activo y la pantalla lo indica; con el trazado apagado, explica cómo activarlo; con RabbitMQ caído, lo dice y se recupera sola al volver.
3. Un mensaje publicado desde el laboratorio aparece en el registro y se anima por exchange → colas a las que RabbitMQ lo enruta; uno sin destino se marca sin enrutar.
4. Un consumidor del laboratorio y uno simulado aparecen como destinos y sus entregas se animan.
5. Con el generador a 100 mensajes por segundo la pantalla sigue fluida, los contadores son exactos y se indica lo omitido.
6. Pausa, cámara lenta, filtro y limpieza funcionan; con movimiento reducido no hay animación.
7. Las gráficas muestran ritmo y mensajes listos, y la cola de un emisor rápido se ve crecer.
8. Las pruebas del backend (incluidas las de integración con el trazado real) y del frontend pasan, con las de las especificaciones 01 a 04.
9. El README documenta el visualizador y el trazado.

## 7. Decisiones tomadas

1. Fuente de datos: trazado nativo de RabbitMQ (*firehose*), no solo lo que ve el backend.
2. El Compose activa el trazado y la aplicación lo detecta, indicando cómo hacerlo a mano si falta.
3. Un visualizador por laboratorio, como pestaña «Flujo», en vez de una sección global con selector de sistema; los componentes son compartidos para reutilizarlos con Kafka.
4. Contenido: diagrama animado, registro de eventos y gráficas.
5. Las colas de destino de una publicación las informa RabbitMQ en el propio trazado (`routed_queues`); no se recalculan a partir de los bindings (decisión corregida tras comprobar el trazado real, T01).

## 8. Puntos a confirmar en el plan

- Cómo hace el Compose para activar el trazado (RabbitMQ no lo permite por configuración) y cómo se recrea el contenedor del usuario sin perder datos.
- Cómo se excluye del flujo el tráfico del propio visualizador.
- Resuelto en T01: ver «Resultados de la verificación» en el plan.

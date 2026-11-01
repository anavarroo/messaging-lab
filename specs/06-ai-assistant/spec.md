# Especificación 06 — Asistente de IA del laboratorio

- **Estado:** Aprobada
- **Fecha:** 2026-10-31

## 1. Contexto y objetivo

El laboratorio tiene ya mucho que mirar: colas que crecen, emisores en marcha, un trazado que puede estar apagado, un flujo en tiempo real. Quien empieza no siempre sabe **qué está pasando ni por qué**.

**Objetivo:** un **asistente de chat** dentro de la aplicación, que responde en español a preguntas sobre el laboratorio (qué es cada pieza, qué ocurre ahora mismo, por qué una cola crece…) usando un **modelo de IA pequeño que corre en local**, sin claves, sin coste y sin que salga ningún dato de la máquina.

**Cómo se hace posible con un modelo pequeño:** el modelo **no razona sobre datos en bruto**. El backend, en cada pregunta, le entrega un **contexto ya preparado**: los datos relevantes del momento, ya resumidos, y las **conclusiones** que unas reglas deterministas ya han sacado («la cola `cocina` crece unos 6 mensajes por segundo: entran 11 y salen 5»). Al modelo le toca solo **explicarlo con claridad**, no deducirlo.

**Dónde vive:** un icono de chat a la derecha de la cabecera de la aplicación, visible en todas las pantallas. El modelo corre en **su propio contenedor**, levantado por el Docker Compose del proyecto.

## 2. Usuarios

- **Persona que aprende RabbitMQ** y quiere preguntar «¿qué es esto?» o «¿por qué pasa esto?» sin salir de la aplicación.
- **Programador de integración industrial** que quiere un diagnóstico rápido de lo que ve en pantalla.

## 3. Requisitos

### RF-1 — El icono y el chat
- **CA-1.1:** La cabecera tendrá, a la derecha, un icono de chat. Al activarlo se despliega un panel de chat pequeño sobre la pantalla actual, sin cambiar de pantalla; al volver a activarlo (o con la tecla Escape) se cierra.
- **CA-1.2:** El panel tendrá la lista de mensajes, un campo para escribir y un botón «Enviar». Se envía también con Intro.
- **CA-1.3:** La respuesta se mostrará **a medida que se genera**, y mientras tanto se verá que el asistente está respondiendo. El usuario podrá **cancelar** una respuesta en curso.
- **CA-1.4:** El historial se conserva mientras la aplicación esté abierta (también al cerrar y abrir el panel o cambiar de pantalla) y no se guarda en ningún otro sitio. Habrá una acción «Nueva conversación».
- **CA-1.5:** El panel será usable con teclado y lector de pantalla, devolverá el foco al icono al cerrarse y se adaptará a pantallas estrechas.
- **CA-1.6:** El texto del asistente se muestra siempre como texto, nunca como HTML.

### RF-2 — Disponibilidad
- **CA-2.1:** El icono indicará si el asistente está disponible. Si el modelo no está listo (contenedor parado, modelo descargándose, error), el panel lo explicará en español y no dejará escribir.
- **CA-2.2:** Si el modelo falla a mitad de una respuesta, o tarda demasiado (más de 2 minutos), se avisará, se conservará lo recibido y se podrá volver a preguntar.
- **CA-2.3:** El resto de la aplicación no depende del asistente: sin él, todo lo demás funciona igual.
- **CA-2.4:** La pantalla «Estado» de la aplicación mostrará, en una **tarjeta propia separada de la de RabbitMQ** (el asistente responderá también sobre Kafka), el **estado del modelo del asistente**: listo, descargándose (la primera vez) o no disponible, con el motivo. Tendrá el mismo botón «Volver a comprobar» que el resto de comprobaciones.

### RF-3 — Contexto preparado
- **CA-3.1:** En cada pregunta, el backend construirá el contexto **en ese momento**, con el estado real: backend, RabbitMQ y su trazado; exchanges, colas (listos y pendientes de confirmar) y bindings; emisores y consumidores simulados con su estado y su ritmo; y el flujo reciente (ritmos y mensajes omitidos).
- **CA-3.2:** El contexto solo incluirá lo **relevante para la pregunta** (por tema), para caber en un presupuesto fijo de unos **1 500 tokens** en total, instrucciones y conversación incluidas.
- **CA-3.3:** El backend añadirá **conclusiones ya calculadas** por reglas deterministas, entre ellas: RabbitMQ no disponible; trazado desactivado; cola que crece (con su ritmo de crecimiento) o que se vacía; cola sin consumidor; mensajes en una dead-letter queue; emisor fallado o parado por límite; consumidor fallado; publicaciones sin enrutar; límite global de mensajes por segundo casi alcanzado.
- **CA-3.4:** Para preguntas conceptuales («¿qué es un binding?»), el contexto incluirá una **ficha breve** del concepto, tomada de un pequeño manual de la aplicación escrito para ello.
- **CA-3.5:** Los **cuerpos de los mensajes no se incluyen** en el contexto (son texto no fiable que podría contener instrucciones dirigidas al modelo). Los nombres de colas, exchanges y emisores sí, tratados como datos.
- **CA-3.6:** Las instrucciones al modelo le ordenarán: responder en español, con frases cortas; usar **solo** el contexto; decir «no lo sé con los datos que tengo» cuando no lo encuentre; no inventar nombres ni cifras.

### RF-4 — Solo lectura
- **CA-4.1:** El asistente **no puede hacer cambios** en el laboratorio: no crea, borra, para ni modifica nada, y el modelo no recibe ninguna herramienta ni acceso directo a RabbitMQ.
- **CA-4.2:** Si el usuario pide una acción («para todos los emisores»), el asistente le explicará **dónde hacerlo** en la aplicación, sin hacerlo.

### RF-5 — El modelo, en su propio contenedor
- **CA-5.1:** El Docker Compose del proyecto levantará un servicio con **Ollama** y el modelo **qwen2.5:3b**. La primera vez se descarga el modelo (unos 2 GB); mientras tanto el asistente indicará que se está preparando.
- **CA-5.2:** El modelo se conservará en un volumen, para no descargarlo de nuevo, y se descargará de la memoria tras **5 minutos sin preguntas**, para no ocupar RAM en vano.
- **CA-5.3:** El servicio tendrá un **límite de memoria** y atenderá **una sola petición a la vez**; las peticiones simultáneas recibirán un aviso de «el asistente está ocupado».
- **CA-5.4:** El modelo, su dirección y el tamaño del contexto serán configurables (por ejemplo, para usar un Ollama instalado fuera de Docker, más rápido en un Mac con chip Apple).
- **CA-5.6:** Se podrá **arrancar todo sin el modelo**: con `OLLAMA_REPLICAS=0` (en `infra/.env` o delante del comando) el Compose no levanta el contenedor del modelo ni descarga nada, y el asistente se muestra como no disponible. Por defecto, el modelo se levanta.
- **CA-5.5:** No se incluirán claves ni secretos en el repositorio, y el servicio del modelo solo será accesible desde la propia máquina.

### RF-6 — Límites
- **CA-6.1:** Una pregunta tendrá como máximo **500 caracteres**; el historial enviado al modelo se limita a las últimas interacciones que quepan en el presupuesto; la respuesta se limita a unos **250 tokens**.
- **CA-6.2:** Los errores se explicarán en español, sin detalles técnicos internos.

## 4. Requisitos no funcionales

- **RNF-1 (Memoria):** el servicio del modelo, con el modelo cargado y el contexto de 4 096 tokens, ocupará como máximo **4 GB**; sin uso, queda cerca de 0.
- **RNF-2 (Latencia):** la primera palabra de la respuesta llegará en menos de **15 segundos** con el modelo ya cargado, en CPU. Esto se mide en la prueba técnica y, si no se alcanza, se ajusta (contexto, modelo o Ollama nativo).
- **RNF-3 (Arquitectura):** el núcleo del asistente (clasificación, contexto, reglas, conversación) no depende de Ollama, de RabbitMQ ni de Spring; Ollama y RabbitMQ son adaptadores. Así se podrá cambiar de modelo o de sistema de mensajería.
- **RNF-4 (Verificabilidad):** las reglas y la preparación del contexto se prueban sin modelo. La calidad de las respuestas se evalúa con un conjunto fijo de preguntas.
- **RNF-5 (Privacidad):** nada sale de la máquina; el historial no se guarda.

## 5. Fuera de alcance

- Acciones sobre el laboratorio (crear, borrar, parar, publicar): el asistente es de solo lectura.
- Memoria entre sesiones, varios usuarios o varias conversaciones.
- Modelos externos o de pago.
- Entrenar o ajustar el modelo.
- Conocimiento de Kafka (llegará con su laboratorio, ampliando el manual y las reglas).
- Responder sobre el contenido de los mensajes.

## 6. Criterios de finalización

1. El icono de chat está en la cabecera, abre y cierra el panel y funciona con teclado.
2. `docker compose up` levanta el modelo; la primera vez lo descarga y el asistente lo indica.
3. Con el laboratorio en marcha, el asistente responde en español a un conjunto fijo de preguntas (conceptuales y de estado) de forma correcta y coherente con lo que se ve en pantalla.
4. Ante una cola que crece, una cola sin consumidor, el trazado apagado y RabbitMQ caído, el asistente da la conclusión correcta.
5. No hace cambios, y ante una petición de acción indica dónde hacerla.
6. Sin el contenedor del modelo, el resto de la aplicación funciona y el chat lo explica.
7. Las pruebas del backend y del frontend pasan, con las de las especificaciones 01 a 05.
8. El README documenta el asistente, su coste de memoria y cómo configurarlo.

## 7. Decisiones tomadas

1. Modelo **local** con Ollama, en su propio contenedor del Compose; **qwen2.5:3b**, que se levanta por defecto y se puede desactivar con `OLLAMA_REPLICAS=0`.
2. **Solo lectura.**
3. El backend entrega un **contexto preparado y conclusiones calculadas por reglas**; el modelo solo explica.
4. Sin herramientas para el modelo, y sin cuerpos de mensajes en el contexto.
5. Historial solo en la pantalla, sin guardarlo.

## 8. Puntos a confirmar en el plan

- Resueltos en T01 y T02: los valores reales de memoria y latencia de qwen2.5:3b (ver «Resultados de la prueba técnica» en el plan) y el mecanismo para no levantar el modelo (`deploy.replicas`).

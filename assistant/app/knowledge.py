"""
El manual de la aplicación: fichas breves de cada concepto, con las palabras del laboratorio y la pestaña donde se ve
cada cosa. Cada ficha es corta a propósito, porque cabe en un contexto pequeño. Mismo contenido que en la especificación 06.
"""

from dataclasses import dataclass

from app.words import normalize


@dataclass(frozen=True)
class Card:
    id: str
    title: str
    text: str
    # fragmentos de palabra, sin tildes, con los que se encuentra la ficha
    keywords: tuple[str, ...]


ACTIONS = "acciones"

# fichas sobre el proyecto y su manejo, no sobre el estado del laboratorio
ABOUT_PROJECT = frozenset({"aplicacion", "tecnologias", "arquitectura", "desarrollo", "reiniciar", "puertos", "modelo",
                           "asistente", "asistente-fuentes", "asistente-despliegue", "asistente-limites",
                           "asistente-problemas"})

# fichas que explican cómo hacer algo fuera de la aplicación (terminal e infra/.env)
HOW_TO = frozenset({"reiniciar", "puertos", "modelo"})

# las fichas, en el orden en que un mensaje se las encuentra
CARDS: tuple[Card, ...] = (
    Card('mensaje', 'Mensaje',
         'Un mensaje es un trozo de información que una aplicación envía a otra. Lleva un contenido y una etiqueta (la routing key). Se publica desde la pestaña «Publicación» y lo genera solo un emisor en «Generador».',
         ('mensaje',)),
    Card('productor', 'Productor',
         'El productor es quien publica los mensajes. En el laboratorio publicas tú desde «Publicación», o lo hacen los emisores del «Generador».',
         ('productor', 'publicador')),
    Card('exchange', 'Exchange',
         'Un exchange recibe los mensajes del productor y decide a qué colas mandarlos, según sus bindings. No guarda nada. Hay tres tipos: direct, fanout y topic. Se crean en la pestaña «Exchanges».',
         ('exchange',)),
    Card('cola', 'Cola',
         'Una cola guarda los mensajes, en orden, hasta que un consumidor los procesa. Sus contadores (listos, pendientes de confirmar, total) se ven en la pestaña «Colas».',
         ('cola', 'buzon')),
    Card('binding', 'Binding',
         'Un binding es la regla que une un exchange con una cola: dice qué mensajes (por su routing key o su patrón) van a esa cola. Sin bindings, un exchange no entrega a ninguna cola. Se crean en la pestaña «Bindings».',
         ('binding', 'enlace')),
    Card('routing-key', 'Routing key',
         'La routing key es la etiqueta que lleva un mensaje. El exchange la compara con los bindings para decidir a qué colas va. En un exchange topic admite los comodines «*» (una palabra) y «#» (varias).',
         ('routing', 'etiqueta', 'comodin', 'patron')),
    Card('consumidor', 'Consumidor',
         'Un consumidor recibe los mensajes de una cola. Hay dos tipos en el laboratorio: los de sesión, que controlas tú mensaje a mensaje en «Consumidores», y los simulados, que consumen solos y se crean en «Generador».',
         ('consumidor', 'consumir', 'consume')),
    Card('acknowledgement', 'Confirmación (acknowledgement)',
         'Cuando un consumidor termina con un mensaje, le responde a RabbitMQ: confirmar (el mensaje se elimina), rechazar y reencolar (vuelve a la cola) o rechazar sin reencolar (va a la dead-letter queue, si la cola tiene una, y si no se descarta).',
         ('acknowledg', 'confirm', 'ack', 'rechaz', 'reencol')),
    Card('dead-letter', 'Dead-letter queue',
         'Una dead-letter queue es una cola especial que recibe los mensajes que otra cola rechaza sin reencolar. Se elige al crear la cola, en la pestaña «Colas». Se pueden ver con «Inspeccionar».',
         ('dead-letter', 'dead letter', 'dlq', 'fallidos')),
    Card('exchange-defecto', 'Exchange por defecto',
         'El exchange por defecto es un exchange sin nombre que existe siempre. Entrega el mensaje a la cola cuyo nombre coincide con la routing key. Por él pasan las publicaciones «directamente a una cola».',
         ('por defecto', 'sin nombre')),
    Card('tipos-exchange', 'Tipos de exchange',
         'Direct: entrega a las colas cuyo binding coincide exactamente con la routing key. Fanout: entrega a todas las colas enlazadas, sin mirar la routing key. Topic: entrega según un patrón con comodines.',
         ('direct', 'fanout', 'topic', 'tipo')),
    Card('sin-enrutar', 'Mensaje sin enrutar',
         'Un mensaje sin enrutar es el que no llega a ninguna cola porque ningún binding coincide. RabbitMQ lo descarta. El laboratorio lo avisa al publicar y el generador lo cuenta como «sin enrutar».',
         ('enrutar', 'descart', 'no llega')),
    Card('reentregado', 'Mensaje reentregado',
         'Un mensaje reentregado es el que RabbitMQ entrega por segunda vez, porque se rechazó y reencoló o porque el consumidor se fue sin confirmarlo. También puede marcarse al inspeccionar una cola.',
         ('reentreg',)),
    Card('pendiente', 'Mensaje pendiente de confirmar',
         'Un mensaje pendiente de confirmar es el que un consumidor ya ha recibido pero todavía no ha confirmado ni rechazado. No se ve al inspeccionar la cola; el contador «pendientes» los cuenta.',
         ('pendiente', 'unacked')),
    Card('trazado', 'Trazado',
         'El trazado (tracing o firehose) es una función de RabbitMQ que copia cada publicación y cada entrega a un exchange interno. La pestaña «Flujo» lo usa para mostrar los mensajes. Si está desactivado (tracing: false), «Flujo» no muestra nada. Se activa con «rabbitmqctl trace_on» y duplica los mensajes dentro de RabbitMQ, así que es solo para laboratorio.',
         ('traz', 'tracing', 'firehose', 'trace')),
    Card('flujo', 'Pestaña Flujo',
         'La pestaña «Flujo» muestra en tiempo real cómo viaja cada mensaje: publicador, exchange, cola y consumidor, con el ritmo de cada uno. Solo dibuja lo que ha tenido mensajes en los últimos 10 segundos. Tiene registro de eventos, filtro, pausa, cámara lenta y gráficas.',
         ('flujo', 'tiempo real', 'diagrama', 'animacion')),
    Card('generador', 'Generador de tráfico',
         'El generador (pestaña «Generador») publica mensajes sin parar, como un sensor o un robot, a la frecuencia elegida. Corre en el servidor y sigue aunque cierres el navegador. «Parar todo» detiene todo.',
         ('generador', 'emisor', 'emite', 'plantilla')),
    Card('limite', 'Límite global del generador',
         'El generador tiene un límite global de mensajes por segundo (100 por defecto, 500 como máximo absoluto), que se cambia en la pestaña «Generador». No se puede iniciar un emisor si la suma superaría el límite.',
         ('limite', 'maximo')),
    Card('acciones', 'Dónde se hace cada cosa',
         'El asistente no puede hacer cambios. Parar todos los emisores y consumidores simulados: botón «Parar todo» en «Generador». Iniciar, pausar o parar un emisor: su tarjeta en «Generador». Crear o eliminar colas, y vaciarlas: «Colas». Exchanges: «Exchanges». Bindings: «Bindings». Publicar: «Publicación». Consumir mensaje a mensaje: «Consumidores». Parar y levantar RabbitMQ o el asistente: botones de su tarjeta en «Estado».',
         ('accion', 'donde se hace')),
    Card('simulado', 'Consumidor simulado',
         'Un consumidor simulado lee de una cola solo, tardando lo que se le indica en procesar cada mensaje y fallando un porcentaje de las veces. Los que fallan se reencolan o van a la dead-letter queue. Sirve para ver cómo se vacía una cola.',
         ('simulado', 'fallo')),
    # sobre el proyecto y su manejo; al final, para que en caso de empate ganen las del laboratorio
    Card('tecnologias', 'Tecnologías y versiones',
         'Backend: Java 21 con Spring Boot 4.1.2, Maven y el cliente oficial de RabbitMQ (amqp-client); su API REST se describe en OpenAPI (backend/api/api.yml) y el código de la API se genera con openapi-generator 7.26.0; pruebas con JUnit 5 y, contra un RabbitMQ real, Testcontainers. Frontend: Angular 21 con TypeScript 5.9 y pruebas con Vitest, servido por nginx. Asistente de IA: Python 3.12 con FastAPI 0.162, uv para las dependencias y pytest para las pruebas. Mensajería: RabbitMQ 4.4 (con su consola de gestión); Apache Kafka llegará más adelante. Modelos de IA locales con Ollama 0.40.13: qwen2.5:3b para responder y bge-m3 para buscar en la documentación. Todo se levanta con Docker Compose.',
         ('tecnolog', 'version', 'java', 'spring', 'angular', 'python', 'fastapi', 'lenguaje', 'framework', 'libreria', 'hecho con', 'esta hecho', 'stack')),
    Card('arquitectura', 'Arquitectura',
         'El navegador habla solo con el frontend (nginx), que reparte las llamadas: /api/assistant va al asistente y el resto de /api al backend. El backend es el único que habla con RabbitMQ y está organizado en capas: controladores (la API), servicios, núcleos sin dependencias (generador, flujo, diagnóstico y control) y adaptadores para RabbitMQ y Docker, para poder añadir Kafka sin tocar el resto. El asistente es un servicio aparte de solo lectura: consulta el backend solo por GET y usa los modelos de Ollama. Para parar y levantar servicios desde «Estado», el backend pide a un intermediario de Docker (docker-proxy) que solo permite listar, arrancar y parar contenedores de este proyecto.',
         ('arquitectura', 'componente', 'servicios', 'capas', 'como esta organizado', 'como funciona el proyecto', 'nginx', 'docker-proxy', 'intermediario')),
    Card('desarrollo', 'Cómo está hecho el proyecto',
         'Es un único repositorio (monorepo) con backend/, frontend/, assistant/, infra/ (Docker Compose), specs/ (especificaciones) y docs/. Se desarrolla por especificaciones: cada funcionalidad tiene en specs/ su spec.md (requisitos), plan.md (diseño) y tasks.md (tareas). Pruebas: «cd backend && ./mvnw test» (y «./mvnw verify» con un RabbitMQ real en Docker), «cd frontend && npm test» y «cd assistant && uv run pytest». El README explica cómo arrancarlo todo.',
         ('monorepo', 'repositorio', 'especificacion', 'spec', 'prueba', 'test', 'desarrollo')),
    Card('reiniciar', 'Parar, levantar y reiniciar servicios',
         'RabbitMQ y el asistente se paran y se levantan con los botones de su tarjeta en «Estado» (solo con el Docker Compose del proyecto). Desde la terminal, en la raíz del repositorio: reiniciar un servicio, «docker compose -f infra/docker-compose.yml restart rabbitmq» (o backend, frontend, assistant, ollama); levantarlo todo tras un cambio, «docker compose -f infra/docker-compose.yml up -d --build»; pararlo todo, «docker compose -f infra/docker-compose.yml down». Con «down -v» también se borran los datos: mensajes de RabbitMQ y modelos descargados.',
         ('reinici', 'restart', 'levantar', 'arrancar', 'parar el', 'detener', 'docker compose', 'apagar')),
    Card('puertos', 'Cambiar los puertos',
         'Los puertos se cambian en infra/.env (cópialo de infra/.env.example si no existe): BACKEND_PORT (8080), FRONTEND_PORT (4200), RABBITMQ_PORT (5672), RABBITMQ_MANAGEMENT_PORT (15672, la consola de RabbitMQ) y OLLAMA_PORT (11434). Después, «docker compose -f infra/docker-compose.yml up -d». Dentro de Docker los servicios siguen usando sus puertos internos, así que no hay que cambiar nada más. En el arranque local, el frontend lee BACKEND_PORT y ASSISTANT_PORT (8000) del mismo archivo.',
         ('puerto', 'port', '8080', '4200', '5672', '15672', '.env', 'ocupado')),
    Card('modelo', 'Cambiar el modelo de IA',
         'Para cambiar el modelo: 1) en infra/.env, pon ASSISTANT_MODEL con el modelo que quieras; 2) ejecuta «docker compose -f infra/docker-compose.yml up -d --build» (la primera vez se descarga). Modelos: qwen2.5:3b es el de por defecto (unos 4 GB de memoria con el de búsqueda y 94 % de acierto). qwen2.5:1.5b es más ligero: usa menos memoria, pero responde peor (no está evaluado). qwen2.5:7b es más potente (97 % de acierto), pero unas 3 veces más lento; necesita además OLLAMA_MEMORY_LIMIT=7g y 10 GB o más para Docker. Para no usar ningún modelo, OLLAMA_REPLICAS=0.',
         ('modelo', 'ligero', 'liviano', 'potente', 'qwen', 'ollama', 'gpu')),
    Card('aplicacion', 'La aplicación y sus apartados',
         'messaging-lab es un laboratorio visual para aprender y comparar sistemas de mensajería. El menú tiene tres secciones: «Estado» (comprueba el backend, RabbitMQ con su trazado y el asistente, y permite pararlos y levantarlos), «Laboratorio RabbitMQ» (disponible) y «Laboratorio Kafka» (todavía no disponible: llegará más adelante). El Laboratorio RabbitMQ tiene ocho pestañas: «Guía» (conceptos y ejemplos guiados), «Exchanges», «Colas», «Bindings», «Publicación», «Consumidores» (consumir mensaje a mensaje), «Generador» (emisores y consumidores simulados) y «Flujo» (los mensajes en tiempo real). Los exchanges pueden ser direct, fanout o topic.',
         ('proyecto', 'aplicacion', 'apartado', 'seccion', 'pestana', 'menu', 'messaging-lab', 'kafka')),
    Card('asistente', 'Cómo funciona el asistente',
         'El asistente es un servicio aparte (assistant/, Python y FastAPI) que responde con un modelo de lenguaje local servido por Ollama. Para cada pregunta: 1) decide de qué trata con un clasificador de palabras clave; 2) prepara un contexto con las conclusiones que calcula el backend con reglas, los datos que tocan, las fichas de este manual y los trozos de documentación más parecidos; 3) el modelo redacta la respuesta y, si le falta algo, puede consultar hasta 4 herramientas de solo lectura. Algunas respuestas no pasan por el modelo, para que sean exactas: el estado general, la actividad, RabbitMQ caído, las peticiones de cambio y cómo cambiar puertos, el modelo o reiniciar.',
         ('como funcionas', 'como funciona el asistente', 'como respondes', 'como piensas', 'asistente', 'chatbot', 'agente', 'herramientas del')),
    Card('asistente-fuentes', 'De dónde saca la información el asistente',
         'Del laboratorio solo lee: en el momento de cada pregunta consulta al backend, por GET, la foto del laboratorio (colas, exchanges, emisores, consumidores, ritmos y trazado), el diagnóstico y la actividad. Nunca ve el contenido de los mensajes. Para los conceptos usa este manual de fichas y una búsqueda por significado, con el modelo bge-m3, en la guía, el README y las especificaciones. No usa internet ni servicios externos: todo pasa en tu máquina. No guarda tus preguntas: la conversación solo vive en la pestaña del navegador y se pierde al recargarla o con «Nueva conversación».',
         ('de donde sacas', 'de donde saca', 'fuentes', 'internet', 'privacidad', 'guardas', 'mis preguntas', 'mis datos', 'contenido de los mensajes', 'embedding', 'bge-m3', 'rag')),
    Card('asistente-despliegue', 'Cómo está levantado el asistente',
         'Con el Docker Compose se levantan tres servicios: assistant (la API del asistente, en el puerto interno 8000; el frontend le envía /api/assistant), ollama (sirve los modelos; su memoria está limitada por OLLAMA_MEMORY_LIMIT, 4 GB por defecto) y ollama-model (descarga los modelos la primera vez y termina). Los modelos se guardan en el volumen ollama-data y el índice de la documentación en assistant-index, así que no se descargan ni se recalculan cada vez. Tras 5 minutos sin preguntas, los modelos salen de la memoria (OLLAMA_KEEP_ALIVE). Dentro de Docker, Ollama usa solo la CPU.',
         ('como esta levantado el asistente', 'como esta montado', 'donde corres', 'donde se ejecuta', 'donde esta el modelo', 'contenedor del asistente', 'ollama-model', 'ollama-data', 'keep_alive', 'volumen')),
    Card('asistente-limites', 'Límites del asistente',
         'Es de solo lectura: no puede cambiar nada del laboratorio. Responde a una pregunta cada vez, de hasta 500 caracteres; recuerda las últimas intervenciones de la conversación, y corta la respuesta si tarda más de 2 minutos. Es un modelo pequeño: puede equivocarse o resumir de más, sobre todo con preguntas muy largas o que piden varias cosas a la vez. Las conclusiones sobre colas, emisores y RabbitMQ las calcula el backend con reglas, así que son fiables. Si una respuesta no te cuadra, compruébala en la pestaña correspondiente o pregunta de forma más concreta. Solo sabe de este laboratorio: para lo demás dirá «No lo sé con los datos que tengo».',
         ('equivoc', 'fiable', 'limitaciones', 'que no puedes', 'alucin', 'recuerdas', 'te acuerdas', 'confiar')),
    Card('asistente-problemas', 'Si el asistente no responde o va lento',
         '«Preparándose»: se está descargando un modelo (la primera vez, unos 3 GB) o indexando la documentación (alrededor de un minuto); espera. «No disponible»: el servicio assistant u ollama no está en marcha (levántalo desde su tarjeta en «Estado» o con «docker compose -f infra/docker-compose.yml up -d»), o se arrancó con OLLAMA_REPLICAS=0. Lento: la primera pregunta tras un rato tarda más porque carga el modelo en memoria, y dentro de Docker solo usa la CPU; da más CPU a Docker, usa un modelo más ligero o, en un Mac, un Ollama instalado fuera de Docker. «Ocupado»: espera a que termine la respuesta anterior o cancélala.',
         ('tardas', 'asistente no responde', 'asistente va lento', 'asistente tarda', 'preparandose', 'asistente no disponible', 'asistente ocupado', 'esta ocupado', 'se queda pensando', 'no contestas'))
)


def card(card_id: str) -> Card:
    return next(card for card in CARDS if card.id == card_id)


def find(question: str | None, limit: int) -> list[Card]:
    """Las fichas que mejor encajan con la pregunta, como mucho `limit`; ninguna si no encaja ninguna."""
    text = normalize(question)
    scored = [(sum(1 for word in card.keywords if word in text), card) for card in CARDS]
    scored = [item for item in scored if item[0] > 0]
    scored.sort(key=lambda item: item[0], reverse=True)
    return [card for _, card in scored[:limit]]


def best_of(question: str | None, ids: frozenset[str]) -> Card | None:
    """La ficha de `ids` que mejor encaja, si encaja al menos tanto como la mejor de todas (en un empate, gana)."""
    text = normalize(question)
    scores = {card.id: sum(1 for word in card.keywords if word in text) for card in CARDS}
    top = max(scores.values(), default=0)
    candidates = [card for card in CARDS if card.id in ids and scores[card.id] > 0 and scores[card.id] == top]
    return candidates[0] if candidates else None

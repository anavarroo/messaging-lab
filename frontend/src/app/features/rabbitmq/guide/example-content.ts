import { LabLink } from './guide-content';

/**
 * Nombres del ejemplo (CA-4.5). Son fijos y se repiten igual en todos los pasos para poder copiarlos tal cual.
 */
export const NAMES = {
  exchange: 'pizzeria',
  fanout: 'avisos',
  kitchen: 'cocina',
  kitchenDlq: 'cocina-fallidos',
  bar: 'barra',
  pizzaKey: 'pedido.pizza',
  drinkKey: 'pedido.bebida',
  dessertKey: 'pedido.postre',
  cook: 'cocinero-1',
  barista: 'barista-1',
  // ejemplo 3, con el generador
  sensors: 'sensores',
  readings: 'lecturas',
  sensorPattern: 'planta1.sensor.#',
  sensorKey: 'planta1.sensor.temperatura.sala2',
  temperatureEmitter: 'Sensor de temperatura',
  robotEmitter: 'Brazo robótico',
  reader: 'lector-1',
} as const;

const N = NAMES;

/**
 * Los textos marcan con [[doble corchete]] lo que hay que escribir o lo que se ve tal cual (nombres, routing
 * keys, cuerpos), para mostrarlo en un formato que se pueda copiar.
 */
export interface ExampleStep {
  id: string;
  title: string;
  /** Pantalla del laboratorio donde se hace. */
  link: LabLink;
  /** «Haz esto»: las acciones, en orden. */
  actions: readonly string[];
  /** «Deberías ver»: lo que aparecerá, para poder compararlo. */
  shouldSee: readonly string[];
  /** «Qué ha pasado» */
  whatHappened: string;
}

const area = (label: string, path: string): LabLink => ({ label, path: `/rabbitmq/${path}` });

const pizzaOrder1 = '{"pizza": "margarita", "mesa": 4}';
const pizzaOrder2 = '{"pizza": "cuatro quesos", "mesa": 7}';
const dessertOrder = '{"postre": "tiramisú"}';
const drinkOrder = '{"bebida": "agua"}';
const noticeBody = 'Cerramos en 10 minutos';

/** El ejemplo principal: una pizzería con un exchange topic, dos colas y una dead-letter queue (CA-4.1, CA-4.3). */
export const MAIN_STEPS: readonly ExampleStep[] = [
  {
    id: 'crear-exchange',
    title: 'Crea el exchange de la pizzería',
    link: area('Exchanges', 'exchanges'),
    actions: [
      `En «Nombre», escribe [[${N.exchange}]].`,
      'En «Tipo», elige [[Topic]].',
      'Pulsa «Crear exchange».',
    ],
    shouldSee: [
      `Un aviso verde: «Exchange «${N.exchange}» creado.»`,
      `En la lista de exchanges, una fila nueva: [[${N.exchange}]], tipo Topic, origen «Creado por ti».`,
    ],
    whatHappened:
      'Has creado la oficina de clasificación. De momento no sabe a dónde mandar nada, porque todavía no hay buzones.',
  },
  {
    id: 'cola-fallidos',
    title: 'Crea la cola de los pedidos fallidos',
    link: area('Colas', 'queues'),
    actions: [
      `En «Nombre», escribe [[${N.kitchenDlq}]].`,
      'Deja «Dead-letter queue (opcional)» en «Sin dead-letter queue».',
      'Pulsa «Crear cola».',
    ],
    shouldSee: [
      `Un aviso: «Cola «${N.kitchenDlq}» creada.»`,
      'En la lista de colas, una fila con sus contadores a 0: Listos 0, Pendientes de confirmar 0, Total 0.',
    ],
    whatHappened:
      'Esta cola es un buzón normal. La creas primero porque será la dead-letter queue de la cocina, y una dead-letter queue tiene que existir antes de poder elegirla.',
  },
  {
    id: 'cola-cocina',
    title: 'Crea la cola de la cocina',
    link: area('Colas', 'queues'),
    actions: [
      `En «Nombre», escribe [[${N.kitchen}]].`,
      `En «Dead-letter queue (opcional)», elige [[${N.kitchenDlq}]].`,
      'Pulsa «Crear cola».',
    ],
    shouldSee: [
      `Un aviso: «Cola «${N.kitchen}» creada.»`,
      `En la lista, la fila de [[${N.kitchen}]] muestra [[${N.kitchenDlq}]] en la columna «Dead-letter queue».`,
    ],
    whatHappened:
      'Has creado el buzón de la cocina y le has dicho que los pedidos que se rechacen sin reencolar se guarden en «cocina-fallidos».',
  },
  {
    id: 'cola-barra',
    title: 'Crea la cola de la barra',
    link: area('Colas', 'queues'),
    actions: [
      `En «Nombre», escribe [[${N.bar}]].`,
      'Deja «Dead-letter queue (opcional)» en «Sin dead-letter queue».',
      'Pulsa «Crear cola».',
    ],
    shouldSee: [
      `Un aviso: «Cola «${N.bar}» creada.»`,
      `La fila de [[${N.bar}]] muestra «—» en la columna «Dead-letter queue».`,
    ],
    whatHappened:
      'Esta cola no tiene dead-letter queue a propósito: más adelante verás qué pasa con un mensaje rechazado cuando no hay a dónde mandarlo.',
  },
  {
    id: 'binding-cocina',
    title: 'Enlaza el exchange con la cocina',
    link: area('Bindings', 'bindings'),
    actions: [
      `En «Exchange», elige [[${N.exchange}]] (Topic).`,
      `En «Cola», elige [[${N.kitchen}]].`,
      `En «Patrón», escribe [[${N.pizzaKey}]].`,
      'Pulsa «Crear binding».',
    ],
    shouldSee: [
      `Un aviso: «Binding creado entre «${N.exchange}» y «${N.kitchen}».»`,
      `En la lista de bindings: [[${N.exchange}]], [[${N.kitchen}]], [[${N.pizzaKey}]].`,
    ],
    whatHappened:
      'Has pegado la nota en el buzón de la cocina: «mandadme los mensajes de la pizzería que lleven la etiqueta pedido.pizza».',
  },
  {
    id: 'binding-barra',
    title: 'Enlaza el exchange con la barra',
    link: area('Bindings', 'bindings'),
    actions: [
      `En «Exchange», elige [[${N.exchange}]] (Topic).`,
      `En «Cola», elige [[${N.bar}]].`,
      `En «Patrón», escribe [[${N.drinkKey}]].`,
      'Pulsa «Crear binding».',
    ],
    shouldSee: [
      `Un aviso: «Binding creado entre «${N.exchange}» y «${N.bar}».»`,
      'La lista de bindings ya tiene dos filas, una para cada cola.',
    ],
    whatHappened:
      'Ahora cada buzón tiene su regla: la cocina recibe los pedidos de pizza y la barra, los de bebida.',
  },
  {
    id: 'publicar-llega',
    title: 'Publica un pedido de pizza',
    link: area('Publicación', 'publish'),
    actions: [
      `En «Exchange», elige [[${N.exchange}]] (Topic).`,
      `En «Routing key», escribe [[${N.pizzaKey}]].`,
      `En «Cuerpo del mensaje», escribe [[${pizzaOrder1}]].`,
      'Pulsa «Publicar».',
    ],
    shouldSee: ['Un aviso verde: «El mensaje se ha publicado y se ha enrutado al menos a una cola.»'],
    whatHappened:
      'El exchange ha leído la etiqueta pedido.pizza, la ha comparado con los bindings y ha dejado el mensaje en la cola «cocina». A «barra» no le ha llegado nada, porque su regla pide pedido.bebida.',
  },
  {
    id: 'publicar-no-llega',
    title: 'Publica un pedido que no llega a ninguna cola',
    link: area('Publicación', 'publish'),
    actions: [
      `En «Exchange», deja [[${N.exchange}]] (Topic).`,
      `En «Routing key», escribe [[${N.dessertKey}]].`,
      `En «Cuerpo del mensaje», escribe [[${dessertOrder}]].`,
      'Pulsa «Publicar».',
    ],
    shouldSee: [
      'Un aviso amarillo: «El mensaje se ha publicado, pero no se ha podido enrutar a ninguna cola y se ha descartado: ningún binding coincide con la routing key.»',
    ],
    whatHappened:
      'Nadie ha pedido pedido.postre, así que el exchange no sabe a qué buzón mandarlo y lo tira. RabbitMQ no da error: por eso es importante fijarse en este aviso.',
  },
  {
    id: 'contadores',
    title: 'Mira cuántos mensajes tiene cada cola',
    link: area('Colas', 'queues'),
    actions: ['Pulsa «Actualizar» en la lista de colas.'],
    shouldSee: [
      `[[${N.kitchen}]]: Listos 1, Pendientes de confirmar 0, Total 1.`,
      `[[${N.bar}]] y [[${N.kitchenDlq}]]: todo a 0.`,
    ],
    whatHappened:
      'En la cocina hay un pedido esperando («listo» significa que está en la cola y todavía nadie lo ha recogido). El pedido del postre no aparece en ninguna parte: se descartó.',
  },
  {
    id: 'consumidor-cocina',
    title: 'Pon al cocinero a trabajar',
    link: area('Consumidores', 'consumers'),
    actions: [
      `En «Cola», elige [[${N.kitchen}]].`,
      'En «Modo de confirmación», deja [[Manual]].',
      `En «Nombre del consumidor», escribe [[${N.cook}]].`,
      'Pulsa «Iniciar consumidor».',
    ],
    shouldSee: [
      `Un aviso: «Consumidor «${N.cook}» iniciado sobre la cola «${N.kitchen}».»`,
      `Una tarjeta de [[${N.cook}]] marcada como «Activo».`,
      `En el registro de mensajes de la tarjeta, el pedido: cuerpo [[${pizzaOrder1}]], exchange [[${N.exchange}]], routing key [[${N.pizzaKey}]], y debajo tres botones: «Confirmar», «Rechazar y reencolar» y «Rechazar sin reencolar».`,
      'Si vas a «Colas» y pulsas «Actualizar»: cocina con Listos 0, Pendientes de confirmar 1, Total 1.',
    ],
    whatHappened:
      'El cocinero ha recogido el pedido, pero RabbitMQ no lo borra todavía: lo marca como «pendiente de confirmar» hasta que el cocinero diga qué ha hecho con él. Mientras no decidas, no recibirá otro.',
  },
  {
    id: 'reencolar',
    title: 'Devuelve el pedido a la cola',
    link: area('Consumidores', 'consumers'),
    actions: ['En el pedido, pulsa «Rechazar y reencolar».'],
    shouldSee: [
      'Debajo del pedido, el aviso: «Rechazado y reencolado: ha vuelto a la cola y se recibirá de nuevo marcado como reentregado.»',
      'Arriba aparece enseguida el mismo pedido otra vez, ahora con la marca «Reentregado» y con sus tres botones.',
    ],
    whatHappened:
      'Has dicho «ahora no puedo con este pedido, devuélvelo». RabbitMQ lo ha puesto de nuevo en la cola, marcado como reentregado, y como el cocinero estaba libre, se lo ha vuelto a dar.',
  },
  {
    id: 'confirmar',
    title: 'Confirma el pedido',
    link: area('Consumidores', 'consumers'),
    actions: ['En el pedido que tiene la marca «Reentregado», pulsa «Confirmar».'],
    shouldSee: [
      'El aviso: «Confirmado: el mensaje se ha eliminado de la cola.»',
      'En «Colas», tras pulsar «Actualizar»: cocina con Listos 0, Pendientes de confirmar 0, Total 0.',
    ],
    whatHappened:
      'El cocinero ha terminado la pizza y ha dado el acuse de recibo. RabbitMQ ya sabe que el pedido está hecho y lo borra para siempre.',
  },
  {
    id: 'publicar-otro',
    title: 'Publica otro pedido de pizza',
    link: area('Publicación', 'publish'),
    actions: [
      `En «Exchange», deja [[${N.exchange}]] (Topic).`,
      `En «Routing key», escribe [[${N.pizzaKey}]].`,
      `En «Cuerpo del mensaje», escribe [[${pizzaOrder2}]].`,
      'Pulsa «Publicar».',
    ],
    shouldSee: [
      'El aviso de que se ha enrutado.',
      `En «Consumidores», el pedido nuevo (cuerpo [[${pizzaOrder2}]]) aparece solo en el registro de [[${N.cook}]], sin marca de reentregado.`,
    ],
    whatHappened:
      'El cocinero estaba esperando, así que el mensaje le ha llegado en cuanto ha entrado en la cola. Ahora lo vas a rechazar para ver adónde va.',
  },
  {
    id: 'rechazar-dlq',
    title: 'Rechaza el pedido sin devolverlo',
    link: area('Consumidores', 'consumers'),
    actions: ['En ese pedido, pulsa «Rechazar sin reencolar».'],
    shouldSee: [
      'El aviso: «Rechazado sin reencolar: el mensaje se ha enviado a la dead-letter queue de la cola.»',
      `En «Colas», tras «Actualizar»: [[${N.kitchen}]] con todo a 0 y [[${N.kitchenDlq}]] con Listos 1.`,
    ],
    whatHappened:
      'Has dicho «este pedido no vale». Como la cola «cocina» tiene una dead-letter queue, RabbitMQ no lo ha tirado: lo ha movido a «cocina-fallidos».',
  },
  {
    id: 'inspeccionar-dlq',
    title: 'Mira qué hay en la cola de fallidos',
    link: area('Colas', 'queues'),
    actions: [`En la fila de [[${N.kitchenDlq}]], pulsa «Inspeccionar».`],
    shouldSee: [
      `Un panel con el pedido: cuerpo [[${pizzaOrder2}]].`,
      `Su exchange es [[${N.exchange}]] y su routing key [[${N.pizzaKey}]]: los de origen, no los de la cola de fallidos.`,
      `«Cola de origen»: [[${N.kitchen}]]. «Motivo»: [[rejected]] (rechazado).`,
      'Un aviso de que inspeccionar no retira los mensajes, pero puede dejarlos marcados como reentregados.',
    ],
    whatHappened:
      'Has abierto el buzón de los fallidos sin sacar nada. RabbitMQ guarda de dónde venía el mensaje y por qué acabó ahí, y el laboratorio te lo enseña para que puedas investigar qué falló.',
  },
  {
    id: 'sin-dlq',
    title: 'Qué pasa si la cola no tiene dead-letter queue',
    link: area('Publicación', 'publish'),
    actions: [
      `En «Publicación», publica en [[${N.exchange}]] con la routing key [[${N.drinkKey}]] y el cuerpo [[${drinkOrder}]].`,
      `En «Consumidores», inicia un consumidor sobre [[${N.bar}]], modo [[Manual]], con el nombre [[${N.barista}]].`,
      'En el pedido que recibe, pulsa «Rechazar sin reencolar».',
    ],
    shouldSee: [
      'El aviso, en rojo oscuro: «El mensaje se ha descartado: se rechazó sin reencolar y la cola no tiene dead-letter queue.»',
      `En «Colas», tras «Actualizar»: [[${N.bar}]] con todo a 0, y [[${N.kitchenDlq}]] sigue con Listos 1: el mensaje descartado no ha ido a ninguna parte.`,
    ],
    whatHappened:
      'La barra no tiene dead-letter queue, así que el mensaje rechazado no tiene a dónde ir y se pierde. Por eso conviene dar una dead-letter queue a las colas cuyos mensajes no quieres perder.',
  },
  {
    id: 'detener-consumidores',
    title: 'Detén a los consumidores',
    link: area('Consumidores', 'consumers'),
    actions: [`En las tarjetas de [[${N.cook}]] y [[${N.barista}]], pulsa «Detener».`],
    shouldSee: ['Cada tarjeta pasa a «Detenido», con el aviso «El consumidor «…» se ha detenido.», y conserva sus mensajes.'],
    whatHappened:
      'Un consumidor detenido ya no recibe mensajes. Si hubiera tenido alguno pendiente de confirmar, habría vuelto a la cola.',
  },
];

/** Variante corta con un exchange fanout, para ver la diferencia con el topic (CA-4.4). */
export const FANOUT_STEPS: readonly ExampleStep[] = [
  {
    id: 'fanout-crear',
    title: 'Crea un exchange fanout',
    link: area('Exchanges', 'exchanges'),
    actions: [
      `En «Nombre», escribe [[${N.fanout}]].`,
      'En «Tipo», elige [[Fanout]].',
      'Pulsa «Crear exchange».',
    ],
    shouldSee: [`Un aviso: «Exchange «${N.fanout}» creado.»`],
    whatHappened: 'Este exchange reparte lo mismo a todos sus buzones, sin mirar etiquetas.',
  },
  {
    id: 'fanout-bindings',
    title: 'Enlázalo con las dos colas',
    link: area('Bindings', 'bindings'),
    actions: [
      `Crea un binding de [[${N.fanout}]] (Fanout) a [[${N.kitchen}]].`,
      `Crea otro binding de [[${N.fanout}]] (Fanout) a [[${N.bar}]].`,
    ],
    shouldSee: [
      'Al elegir el exchange fanout, el formulario no te pide routing key y explica que difunde a todas sus colas.',
      'Dos avisos de «Binding creado…» y dos filas nuevas con la routing key vacía.',
    ],
    whatHappened:
      'Un fanout no compara etiquetas, así que sus bindings no llevan routing key: cada cola enlazada recibe todo.',
  },
  {
    id: 'fanout-publicar',
    title: 'Publica un aviso para todos',
    link: area('Publicación', 'publish'),
    actions: [
      `En «Exchange», elige [[${N.fanout}]] (Fanout). Verás que no pide routing key.`,
      `En «Cuerpo del mensaje», escribe [[${noticeBody}]].`,
      'Pulsa «Publicar».',
    ],
    shouldSee: [
      'El aviso de que se ha enrutado.',
      `En «Colas», tras «Actualizar»: [[${N.kitchen}]] con Listos 1 y [[${N.bar}]] con Listos 1.`,
    ],
    whatHappened:
      'Un solo mensaje, dos copias: una en cada cola. Con el topic habrías tenido que elegir la etiqueta justa para cada una.',
  },
];

/** Último paso opcional: dejar el laboratorio limpio (CA-4.6). */
export const CLEANUP_STEPS: readonly ExampleStep[] = [
  {
    id: 'limpiar-colas',
    title: 'Elimina las colas',
    link: area('Colas', 'queues'),
    actions: [
      `Elimina [[${N.kitchen}]], [[${N.bar}]] y, la última, [[${N.kitchenDlq}]]: en cada fila pulsa «Eliminar» y confirma.`,
    ],
    shouldSee: [
      'Un diálogo que avisa de que se perderán los mensajes y los bindings.',
      `Al eliminar [[${N.kitchenDlq}]] sin haber eliminado antes [[${N.kitchen}]], el diálogo la nombra como cola afectada y avisa de que sus mensajes rechazados se descartarán.`,
    ],
    whatHappened:
      'Al eliminar una cola desaparecen también sus bindings. Por eso conviene empezar por la cocina y dejar la dead-letter queue para el final.',
  },
  {
    id: 'limpiar-exchanges',
    title: 'Elimina los exchanges',
    link: area('Exchanges', 'exchanges'),
    actions: [`Elimina [[${N.exchange}]] y [[${N.fanout}]]: pulsa «Eliminar» y confirma.`],
    shouldSee: ['El diálogo avisa de que también se eliminarán los bindings del exchange.', 'Las listas de colas y de exchanges vuelven a estar como al principio.'],
    whatHappened: 'El laboratorio queda limpio y listo para que pruebes tus propios experimentos.',
  },
];

/**
 * Ejemplo 3: tráfico continuo con el generador, visto en tiempo real desde «Flujo». Es independiente de los otros dos:
 * crea sus propios exchange y cola.
 */
export const GENERATOR_STEPS: readonly ExampleStep[] = [
  {
    id: 'gen-exchange',
    title: 'Crea el exchange de los sensores',
    link: area('Exchanges', 'exchanges'),
    actions: [`En «Nombre», escribe [[${N.sensors}]].`, 'En «Tipo», elige [[Topic]].', 'Pulsa «Crear exchange».'],
    shouldSee: [`Un aviso verde: «Exchange «${N.sensors}» creado.»`],
    whatHappened: 'Es la oficina por la que pasarán las lecturas de los sensores.',
  },
  {
    id: 'gen-cola',
    title: 'Crea la cola de las lecturas',
    link: area('Colas', 'queues'),
    actions: [`En «Nombre», escribe [[${N.readings}]].`, 'Deja «Dead-letter queue» sin elegir.', 'Pulsa «Crear cola».'],
    shouldSee: [`Un aviso: «Cola «${N.readings}» creada.»`, `Una fila nueva con [[${N.readings}]] y todos los contadores a 0.`],
    whatHappened: 'Aquí se irán acumulando las lecturas hasta que alguien las consuma.',
  },
  {
    id: 'gen-binding',
    title: 'Enlaza el exchange con la cola',
    link: area('Bindings', 'bindings'),
    actions: [
      `Crea un binding de [[${N.sensors}]] a [[${N.readings}]].`,
      `En «Routing key», escribe [[${N.sensorPattern}]].`,
      'Pulsa «Crear binding».',
    ],
    shouldSee: ['Un aviso de «Binding creado…» y una fila nueva con ese patrón.'],
    whatHappened:
      'El patrón dice: «a esta cola, todo lo que empiece por planta1.sensor.». El «#» sustituye a cualquier número de palabras.',
  },
  {
    id: 'gen-flujo-vacio',
    title: 'Abre «Flujo» antes de que pase nada',
    link: area('Flujo', 'flow'),
    actions: ['Entra en la pestaña «Flujo» y déjala abierta en otra pestaña del navegador, o vuelve aquí después de cada paso.'],
    shouldSee: [
      'El mensaje «No hay flujo activo ahora mismo…» y, debajo, el registro vacío («Aún no ha pasado ningún mensaje.»).',
      'Si en cambio sale un aviso rojo diciendo que el trazado está desactivado, sigue las instrucciones que da: es lo que alimenta esta pantalla.',
    ],
    whatHappened:
      'Esta pantalla solo dibuja lo que tiene mensajes pasando por él ahora mismo. Como todavía no hay ninguno, no hay nada que dibujar.',
  },
  {
    id: 'gen-emisor-exchange',
    title: 'Crea un emisor que publica en el exchange',
    link: area('Generador', 'generator'),
    actions: [
      `En el panel «Emisores», en «Nuevo emisor», elige en «Plantilla del catálogo» [[${N.temperatureEmitter}]] y pulsa «Usar plantilla».`,
      'Pulsa «Vista previa» para ver un mensaje de ejemplo: cada vez lleva valores distintos.',
      `En «Destino» deja marcado «A un exchange» y elige [[${N.sensors}]] en «Exchange».`,
      `Comprueba que «Routing key» ha quedado como [[${N.sensorKey}]] y que la frecuencia es fija, cada [[1000]] ms.`,
      'Pulsa «Crear emisor» y, en la lista de emisores, «Iniciar».',
    ],
    shouldSee: [
      `Un aviso: «Emisor «${N.temperatureEmitter}» creado. Está parado…»`,
      'Tras «Iniciar», su tarjeta dice [[En marcha]] y sus «Publicados» suben cada segundo, con un «Ritmo real» de 1 por segundo.',
    ],
    whatHappened:
      'El emisor corre en el servidor: seguirá publicando aunque cambies de pestaña, hasta que lo pares. Cada mensaje sale con la routing key de la plantilla.',
  },
  {
    id: 'gen-ver-emisor',
    title: 'Mira cómo viaja cada mensaje',
    link: area('Flujo', 'flow'),
    actions: [
      'Vuelve a «Flujo».',
      'Mira el diagrama y el registro de eventos. Pulsa «Ver» en una fila para abrir el mensaje.',
    ],
    shouldSee: [
      `En el diagrama, tres elementos unidos: [[${N.temperatureEmitter}]] (publicador) → [[${N.sensors}]] (exchange) → [[${N.readings}]] (cola). Un punto recorre el camino de cada mensaje.`,
      `En el registro, filas de tipo «Publicación» con el recorrido «${N.sensors} → ${N.readings}» y la routing key ${N.sensorKey}.`,
      `La cola [[${N.readings}]] muestra cada vez más «listos»: nadie los consume todavía. La gráfica «Mensajes listos» sube.`,
    ],
    whatHappened:
      'Estás viendo datos reales: RabbitMQ copia cada publicación y cada entrega a su «trazado», y esta pantalla lo dibuja. Un mensaje por segundo es lo bastante lento para seguirlo con los ojos.',
  },
  {
    id: 'gen-consumidor',
    title: 'Crea un consumidor simulado',
    link: area('Generador', 'generator'),
    actions: [
      `En el panel «Consumidores simulados», en «Nombre» escribe [[${N.reader}]].`,
      `En «Cola» elige [[${N.readings}]].`,
      'En «Tiempo de proceso por mensaje», deja «Fijo» y escribe [[200]] en «Tarda (ms)».',
      'Deja «Porcentaje de fallos» en [[0]].',
      'Pulsa «Crear consumidor» y, en su tarjeta, «Iniciar».',
    ],
    shouldSee: [
      `Un aviso: «Consumidor «${N.reader}» creado. Está parado…»`,
      'Tras «Iniciar», su tarjeta dice [[En marcha]] y sus «Procesados» empiezan a subir.',
    ],
    whatHappened:
      'Un consumidor simulado lee de la cola solo, tardando lo que le has dicho en procesar cada mensaje. Como tarda 200 ms, puede con unos 5 mensajes por segundo; el emisor solo envía 1.',
  },
  {
    id: 'gen-ver-consumidor',
    title: 'Mira cómo se vacía la cola',
    link: area('Flujo', 'flow'),
    actions: ['Vuelve a «Flujo» y mira el diagrama, el registro y las gráficas durante unos segundos.'],
    shouldSee: [
      `Aparece un cuarto elemento, [[${N.reader}]] (consumidor), unido a la cola [[${N.readings}]].`,
      'En el registro salen filas de tipo «Entrega» junto a las de «Publicación».',
      `En la cola, los «listos» bajan hasta 0 y se quedan ahí; en la gráfica «Mensajes listos» la línea cae.`,
    ],
    whatHappened:
      'Entran 1 mensaje por segundo y el consumidor retira hasta 5, así que la cola se vacía. Cuando un consumidor es más rápido que quien publica, la cola no se acumula.',
  },
  {
    id: 'gen-emisor-cola',
    title: 'Crea un emisor que publica directamente en la cola',
    link: area('Generador', 'generator'),
    actions: [
      `En «Nuevo emisor», elige en «Plantilla del catálogo» [[${N.robotEmitter}]] y pulsa «Usar plantilla».`,
      `En «Destino», marca [[Directamente a una cola]] y elige [[${N.readings}]] en «Cola».`,
      'Pulsa «Crear emisor» y, en su tarjeta, «Iniciar».',
    ],
    shouldSee: [
      `Un aviso: «Emisor «${N.robotEmitter}» creado…» y su tarjeta, con el destino «Cola «${N.readings}»», cada 100 ms.`,
      'Tras «Iniciar», sus «Publicados» suben unos 10 por segundo.',
    ],
    whatHappened:
      'Este emisor no usa tu exchange: publica «directamente en la cola». En realidad RabbitMQ lo hace pasar por un exchange sin nombre, el exchange por defecto.',
  },
  {
    id: 'gen-ver-cola',
    title: 'Mira qué pasa cuando se publica más rápido de lo que se consume',
    link: area('Flujo', 'flow'),
    actions: ['Vuelve a «Flujo» y observa unos 20 segundos.'],
    shouldSee: [
      `Aparecen [[${N.robotEmitter}]] y un exchange llamado [[(exchange por defecto)]], los dos unidos a [[${N.readings}]].`,
      'En el registro, las publicaciones del brazo tienen el recorrido «(exchange por defecto) → lecturas» y la routing key lecturas.',
      `Ahora entran unos 11 mensajes por segundo y el consumidor solo retira 5: los «listos» de [[${N.readings}]] suben sin parar, y la gráfica «Mensajes listos» sube.`,
    ],
    whatHappened:
      'Cuando el exchange por defecto recibe un mensaje, lo entrega a la cola cuyo nombre coincide con la routing key. Y ves el problema típico de la mensajería: si se publica más rápido de lo que se consume, la cola crece.',
  },
  {
    id: 'gen-controles',
    title: 'Sigue un mensaje con calma',
    link: area('Flujo', 'flow'),
    actions: [
      'En «Filtrar por nombre o routing key», escribe [[planta1.#]].',
      'En «Velocidad de la animación», elige [[Cámara lenta (÷10)]].',
      'Pulsa «Pausar animación» y luego «Reanudar animación».',
      'Borra el filtro.',
    ],
    shouldSee: [
      'Con el filtro, el registro y los puntos solo enseñan los mensajes del sensor de temperatura (los del brazo tienen la routing key «lecturas» y quedan fuera).',
      'En cámara lenta, cada punto tarda unos 7 segundos en recorrer un tramo.',
      'En pausa, los puntos se quedan quietos mientras el registro sigue contando.',
    ],
    whatHappened:
      'El filtro admite los mismos comodines que un exchange topic: «*» sustituye una palabra y «#» varias. Sirve para centrarte en un solo emisor cuando hay mucho tráfico.',
  },
  {
    id: 'gen-parar',
    title: 'Para todo',
    link: area('Generador', 'generator'),
    actions: ['Pulsa «Parar todo», arriba del todo.', 'Vuelve a «Flujo» y espera unos 10 segundos.'],
    shouldSee: [
      'Un aviso: «Se ha parado todo lo que estaba en marcha.» y todas las tarjetas en [[Parado]].',
      'En «Flujo», los elementos van desapareciendo del diagrama hasta que sale otra vez «No hay flujo activo ahora mismo».',
    ],
    whatHappened:
      'Los emisores y consumidores simulados no se detienen solos: corren en el servidor hasta que los paras. «Parar todo» es el botón de seguridad. Y el diagrama solo dibuja lo que tiene mensajes pasando por él.',
  },
];

/** Último paso opcional del ejemplo 3: dejar el laboratorio limpio. */
export const GENERATOR_CLEANUP_STEPS: readonly ExampleStep[] = [
  {
    id: 'gen-limpiar-generador',
    title: 'Elimina los emisores y el consumidor',
    link: area('Generador', 'generator'),
    actions: [`Pulsa «Eliminar» en [[${N.temperatureEmitter}]], en [[${N.robotEmitter}]] y en [[${N.reader}]], y confirma cada vez.`],
    shouldSee: ['Un diálogo de confirmación por cada uno y un aviso de que se ha eliminado.'],
    whatHappened: 'Los mensajes que ya publicaron se quedan en su cola; lo que desaparece es el emisor o el consumidor.',
  },
  {
    id: 'gen-limpiar-colas',
    title: 'Elimina la cola y el exchange',
    link: area('Colas', 'queues'),
    actions: [
      `En «Colas», elimina [[${N.readings}]].`,
      `En «Exchanges», elimina [[${N.sensors}]].`,
    ],
    shouldSee: ['Los diálogos avisan de que se perderán los mensajes y los bindings.', 'Las listas vuelven a estar como al principio.'],
    whatHappened: 'El laboratorio queda limpio. Si tenías muchos mensajes acumulados, borrar la cola es la forma más rápida de deshacerte de ellos.',
  },
];

/** Todos los pasos, en el orden en que se leen. */
export const ALL_STEPS: readonly ExampleStep[] = [
  ...MAIN_STEPS,
  ...FANOUT_STEPS,
  ...CLEANUP_STEPS,
  ...GENERATOR_STEPS,
  ...GENERATOR_CLEANUP_STEPS,
];

export interface TextPart {
  text: string;
  /** Es un valor que se escribe o se ve tal cual, y se muestra en formato de código. */
  code: boolean;
}

/** Separa un texto en partes normales y partes marcadas con [[doble corchete]]. */
export function toParts(text: string): TextPart[] {
  const parts: TextPart[] = [];
  const pattern = /\[\[(.+?)\]\]/g;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > last) {
      parts.push({ text: text.slice(last, match.index), code: false });
    }
    parts.push({ text: match[1], code: true });
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    parts.push({ text: text.slice(last), code: false });
  }
  return parts;
}

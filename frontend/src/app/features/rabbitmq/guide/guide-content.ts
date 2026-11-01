/** Enlace a una pantalla del laboratorio. */
export interface LabLink {
  label: string;
  path: string;
}

/** Un elemento de RabbitMQ explicado con los cuatro títulos fijos de la guía (CA-2.1). */
export interface GuideElement {
  id: string;
  name: string;
  /** «En una frase» */
  inOneSentence: string;
  /** «Piénsalo así»: una analogía de la vida real. */
  think: string;
  /** «Ejemplo» */
  example: string;
  /** «Dónde lo ves en el laboratorio» */
  whereInLab: string;
  links: readonly LabLink[];
}

export interface TopicPatternExample {
  pattern: string;
  receives: string;
  doesNotReceive: string;
}

export interface Idea {
  id: string;
  title: string;
  paragraphs: readonly string[];
}

export interface Trouble {
  id: string;
  problem: string;
  causes: readonly string[];
  solution: string;
}

export interface GlossaryEntry {
  term: string;
  definition: string;
}

const lab = (label: string, path: string): LabLink => ({ label, path: `/rabbitmq/${path}` });

/**
 * Los elementos, en el orden en que un mensaje se los encuentra. Todos usan la misma analogía: el correo postal.
 */
export const ELEMENTS: readonly GuideElement[] = [
  {
    id: 'mensaje',
    name: 'Mensaje',
    inOneSentence: 'Un mensaje es un trozo de información que una aplicación quiere hacer llegar a otra.',
    think:
      'Es una carta. Tiene un contenido (lo que has escrito dentro) y una etiqueta por fuera (la routing key) que dice a qué tipo de asunto se refiere.',
    example:
      'Una pizzería recibe un pedido y crea el mensaje {"pizza": "margarita", "mesa": 4} con la etiqueta pedido.pizza.',
    whereInLab:
      'Lo escribes en «Publicación» y lo ves en «Colas» (al inspeccionar una cola) y en «Consumidores» (cuando llega).',
    links: [lab('Publicación', 'publish')],
  },
  {
    id: 'productor',
    name: 'Productor',
    inOneSentence: 'El productor es la aplicación (o la persona) que envía los mensajes.',
    think: 'Es quien escribe la carta y la deja en la oficina de correos. No sabe quién la leerá, ni cuántos.',
    example:
      'La aplicación de la sala de la pizzería es el productor: cada vez que un camarero apunta un pedido, publica un mensaje.',
    whereInLab: 'En este laboratorio el productor eres tú: publicas mensajes desde la pantalla «Publicación».',
    links: [lab('Publicación', 'publish')],
  },
  {
    id: 'exchange',
    name: 'Exchange',
    inOneSentence:
      'Un exchange recibe los mensajes del productor y decide a qué colas mandarlos. No guarda nada.',
    think:
      'Es la oficina de clasificación de correos: recibe todas las cartas, mira la etiqueta y las reparte a los buzones que correspondan. Si una carta no encaja con ningún buzón, la tira.',
    example:
      'El exchange «pizzeria» recibe todos los pedidos. Los de pizza van a la cocina y los de bebida van a la barra.',
    whereInLab: 'Los creas y los eliminas en «Exchanges». Hay tres tipos: direct, fanout y topic (más abajo).',
    links: [lab('Exchanges', 'exchanges')],
  },
  {
    id: 'cola',
    name: 'Cola',
    inOneSentence: 'Una cola es donde esperan los mensajes hasta que alguien los recoge. Guarda su orden de llegada.',
    think:
      'Es un buzón con cola de espera: las cartas se amontonan en el orden en que llegan y se entregan de la más antigua a la más reciente.',
    example:
      'La cola «cocina» guarda los pedidos de pizza. Si hay tres pedidos esperando, el cocinero cogerá primero el que llegó antes.',
    whereInLab:
      'Las creas, las vacías y las eliminas en «Colas». Allí ves cuántos mensajes tiene cada una: listos, pendientes de confirmar y total.',
    links: [lab('Colas', 'queues')],
  },
  {
    id: 'binding',
    name: 'Binding',
    inOneSentence: 'Un binding es la regla que une un exchange con una cola y decide qué mensajes le llegan.',
    think:
      'Es la nota pegada en el buzón que dice: «a este buzón, mandadle las cartas del exchange con esta etiqueta». Sin esa nota, el buzón no recibe nada, aunque exista.',
    example:
      'El binding «pizzeria → cocina con pedido.pizza» hace que a la cocina lleguen los pedidos de pizza y ningún otro.',
    whereInLab: 'Los creas y los eliminas en «Bindings». Una cola puede tener varios.',
    links: [lab('Bindings', 'bindings')],
  },
  {
    id: 'routing-key',
    name: 'Routing key',
    inOneSentence:
      'La routing key es la etiqueta que lleva cada mensaje por fuera, y con la que se comparan los bindings.',
    think: 'Es el asunto de la carta, escrito en el sobre. El exchange solo lee el sobre, nunca el contenido.',
    example:
      'Un pedido de bebida lleva la routing key pedido.bebida. Un binding que pida pedido.bebida lo recibirá; uno que pida pedido.pizza, no.',
    whereInLab:
      'La escribes al publicar en «Publicación», y al crear un binding en «Bindings» (donde puede ser un patrón en un exchange topic).',
    links: [lab('Publicación', 'publish'), lab('Bindings', 'bindings')],
  },
  {
    id: 'consumidor',
    name: 'Consumidor',
    inOneSentence: 'Un consumidor es quien recoge mensajes de una cola y los procesa.',
    think: 'Es la persona que abre el buzón, saca las cartas y se pone a trabajar con ellas.',
    example:
      'El cocinero es el consumidor de la cola «cocina»: coge un pedido, prepara la pizza y pasa al siguiente.',
    whereInLab:
      'Los inicias y los detienes en «Consumidores». Puedes tener varios sobre la misma cola, y cada uno recibe mensajes distintos.',
    links: [lab('Consumidores', 'consumers')],
  },
  {
    id: 'acknowledgement',
    name: 'Acknowledgement (confirmación)',
    inOneSentence:
      'Un acknowledgement es la respuesta del consumidor a RabbitMQ para decirle qué ha hecho con un mensaje.',
    think:
      'Es el acuse de recibo. Mientras no respondes, RabbitMQ no sabe si la carta se ha procesado, así que la tiene «en tus manos» y no se la da a nadie más. Tienes tres respuestas posibles: Confirmar, Rechazar y reencolar, o Rechazar sin reencolar.',
    example:
      'El cocinero termina la pizza y confirma: el pedido desaparece de la cola. Si se queda sin ingredientes, rechaza el pedido y lo reencola para que lo haga otro. Si el pedido es un disparate, lo rechaza sin reencolar.',
    whereInLab:
      'En «Consumidores», con un consumidor en modo manual, cada mensaje tiene tres botones. En modo automático, el consumidor confirma solo cada mensaje al recibirlo.',
    links: [lab('Consumidores', 'consumers')],
  },
  {
    id: 'dead-letter-queue',
    name: 'Dead-letter queue (cola de mensajes no entregables)',
    inOneSentence:
      'Una dead-letter queue es una cola normal a la que van los mensajes que otra cola ha rechazado sin reencolar.',
    think:
      'Es el buzón de «cartas que no se han podido procesar». Así no se pierden y alguien puede revisarlas después para ver qué pasó.',
    example:
      'Los pedidos rechazados sin reencolar de la cola «cocina» van a «cocina-fallidos», donde el encargado los revisa.',
    whereInLab:
      'Al crear una cola en «Colas» eliges, si quieres, su dead-letter queue entre las colas que ya existen. Al inspeccionar esa cola ves de qué cola vino cada mensaje y por qué.',
    links: [lab('Colas', 'queues')],
  },
];

/** Ejemplos de patrones del exchange topic (CA-2.2). */
export const TOPIC_EXAMPLES: readonly TopicPatternExample[] = [
  { pattern: 'pedido.pizza', receives: 'pedido.pizza', doesNotReceive: 'pedido.bebida, pedido.pizza.grande' },
  { pattern: 'pedido.*', receives: 'pedido.pizza, pedido.bebida', doesNotReceive: 'pedido, pedido.pizza.grande' },
  { pattern: 'pedido.#', receives: 'pedido, pedido.pizza, pedido.pizza.grande', doesNotReceive: 'oferta.pizza' },
  { pattern: '*.pizza', receives: 'pedido.pizza, oferta.pizza', doesNotReceive: 'pizza, pedido.pizza.grande' },
  { pattern: '#', receives: 'cualquier mensaje', doesNotReceive: 'nada: lo recibe todo' },
];

/** Las ideas que más confunden a quien empieza (CA-2.3). */
export const IDEAS: readonly Idea[] = [
  {
    id: 'exchange-por-defecto',
    title: 'El exchange por defecto',
    paragraphs: [
      'RabbitMQ trae un exchange especial sin nombre. Es un atajo: si publicas en él usando como routing key el nombre de una cola, el mensaje va directo a esa cola, sin necesidad de crear ningún binding.',
      'Sirve para probar rápido. Por eso en «Bindings» no se puede crear ni borrar nada sobre él: RabbitMQ ya lo gestiona solo.',
    ],
  },
  {
    id: 'mensaje-sin-cola',
    title: 'Qué pasa si un mensaje no llega a ninguna cola',
    paragraphs: [
      'El exchange no guarda nada. Si ningún binding coincide con la routing key, RabbitMQ acepta el mensaje pero lo tira: desaparece sin dar error.',
      'Por eso el laboratorio te avisa con claridad: «no se ha podido enrutar a ninguna cola y se ha descartado». Cuando lo veas, revisa los bindings.',
    ],
  },
  {
    id: 'pendiente-de-confirmar',
    title: 'Qué es un mensaje «pendiente de confirmar»',
    paragraphs: [
      'Es un mensaje que ya se ha entregado a un consumidor, pero el consumidor todavía no ha dicho qué ha hecho con él. Cuenta en el total de la cola, pero ya no está «listo» para otros consumidores.',
      'No aparece al inspeccionar la cola, porque está en manos del consumidor. Si ese consumidor se detiene antes de decidir, el mensaje vuelve a la cola como listo.',
      'En el laboratorio, un consumidor manual retiene un solo mensaje a la vez: hasta que no resuelves el actual, no recibe el siguiente.',
    ],
  },
  {
    id: 'reentregado',
    title: 'Por qué un mensaje aparece como «reentregado»',
    paragraphs: [
      'RabbitMQ marca como «reentregado» un mensaje que ya se había entregado antes. Sucede cuando lo rechazas y lo reencolas, cuando un consumidor se detiene con un mensaje pendiente, o cuando se corta la conexión.',
      'También puede ocurrir al inspeccionar una cola: RabbitMQ no permite mirar sin tocar, así que la inspección puede dejar los mensajes marcados como reentregados aunque nadie los haya procesado. No es un fallo, es una limitación de RabbitMQ.',
    ],
  },
  {
    id: 'reparto',
    title: 'Dos consumidores en la misma cola se reparten los mensajes',
    paragraphs: [
      'Si dos consumidores leen de la misma cola, cada mensaje lo recibe uno solo, no los dos. RabbitMQ los va repartiendo para que el trabajo se comparta, como dos cocineros que se turnan los pedidos.',
      'Si quieres que un mismo mensaje llegue a varias colas, el camino es distinto: varios bindings, o un exchange fanout.',
    ],
  },
];

/** Los despistes más habituales y su solución (CA-5.1). */
export const TROUBLES: readonly Trouble[] = [
  {
    id: 'no-llega',
    problem: 'Mi mensaje no llega a la cola',
    causes: [
      'No hay ningún binding entre el exchange y la cola.',
      'La routing key del mensaje no coincide con la del binding. Comprueba mayúsculas, espacios y puntos: «pedido.pizza» y «Pedido.Pizza» son distintas.',
      'Has publicado en otro exchange del que querías.',
    ],
    solution:
      'Mira en «Bindings» los de ese exchange y compara su routing key con la del mensaje. Para comprobar que la cola funciona, publica en el exchange por defecto usando el nombre de la cola como routing key.',
  },
  {
    id: 'patron',
    problem: 'El patrón del topic no coincide',
    causes: [
      '«*» sustituye exactamente una palabra: «pedido.*» no recibe «pedido» a secas ni «pedido.pizza.grande».',
      '«#» sustituye cero o más palabras: usa «pedido.#» si quieres también el «pedido» a secas.',
      '«*» y «#» tienen que ser una palabra entera: «ped*» o «pedido.p#» no valen.',
      'Las palabras se separan con un punto, sin puntos al principio, al final ni dos seguidos.',
    ],
    solution: 'Prueba con la tabla de ejemplos de la sección de los tipos de exchange y compara tu patrón con ella.',
  },
  {
    id: 'consumidor-sin-nada',
    problem: 'El consumidor no recibe nada',
    causes: [
      'La cola no tiene mensajes listos: mira sus contadores en «Colas».',
      'Los mensajes están «pendientes de confirmar» en otro consumidor.',
      'Es un consumidor manual con un mensaje sin resolver: no recibirá el siguiente hasta que confirmes o rechaces el actual.',
      'El consumidor se ha detenido o se ha cortado la conexión: verás un aviso con la opción «Reconectar».',
    ],
    solution: 'Mira los contadores de la cola y el estado del consumidor. Si hace falta, detén el consumidor y crea otro.',
  },
  {
    id: 'sigue-ahi',
    problem: 'El mensaje sigue en la cola tras consumirlo',
    causes: [
      'Con un consumidor manual, el mensaje no desaparece hasta que lo confirmas. Mientras tanto aparece como pendiente de confirmar.',
      'Si detienes el consumidor sin confirmar, el mensaje vuelve a la cola.',
    ],
    solution:
      'Pulsa «Confirmar» en el mensaje. Si prefieres no tener que hacerlo, usa un consumidor en modo automático, que confirma cada mensaje al recibirlo.',
  },
  {
    id: 'reentregado-sin-rechazar',
    problem: 'Un mensaje aparece reentregado sin que yo lo haya rechazado',
    causes: [
      'Lo has inspeccionado: RabbitMQ no permite mirar sin tocar y marca los mensajes como reentregados.',
      'Detuviste un consumidor, o se cortó la conexión, con ese mensaje pendiente de confirmar.',
      'Cerraste o recargaste la pestaña con un consumidor activo.',
    ],
    solution: 'No pasa nada: el contenido del mensaje no cambia. Es solo una marca informativa de RabbitMQ.',
  },
];

/** Glosario (CA-2.4): todos los términos de la guía, con su definición, por orden alfabético. */
export const GLOSSARY: readonly GlossaryEntry[] = [
  { term: 'Acknowledgement', definition: 'La respuesta de un consumidor a RabbitMQ sobre qué ha hecho con un mensaje.' },
  { term: 'Binding', definition: 'La regla que une un exchange con una cola y decide qué mensajes le llegan.' },
  { term: 'Cola', definition: 'El lugar donde esperan los mensajes, en orden de llegada, hasta que alguien los recoge.' },
  { term: 'Confirmar', definition: 'Decirle a RabbitMQ que el mensaje se ha procesado bien. El mensaje se elimina de la cola.' },
  { term: 'Consumidor', definition: 'Quien recoge mensajes de una cola y los procesa.' },
  {
    term: 'Dead-letter queue',
    definition: 'Una cola normal a la que van los mensajes rechazados sin reencolar por otra cola.',
  },
  { term: 'Direct', definition: 'Tipo de exchange que entrega a las colas cuya routing key de binding es exactamente la del mensaje.' },
  { term: 'Enrutar', definition: 'Hacer llegar un mensaje desde un exchange a una o varias colas. «No enrutado» es que no llegó a ninguna.' },
  { term: 'Exchange', definition: 'Recibe los mensajes del productor y decide a qué colas mandarlos. No guarda nada.' },
  { term: 'Exchange por defecto', definition: 'Un exchange sin nombre que entrega el mensaje directamente a la cola cuyo nombre es la routing key.' },
  { term: 'Fanout', definition: 'Tipo de exchange que entrega una copia a todas sus colas, sin mirar la routing key.' },
  { term: 'Listo', definition: 'Un mensaje que está en la cola esperando a que un consumidor lo reciba.' },
  { term: 'Mensaje', definition: 'Un trozo de información que una aplicación quiere hacer llegar a otra, con su routing key.' },
  { term: 'Patrón', definition: 'La routing key de un binding en un exchange topic, que puede llevar comodines «*» y «#».' },
  {
    term: 'Pendiente de confirmar',
    definition: 'Un mensaje entregado a un consumidor que todavía no ha dicho qué ha hecho con él.',
  },
  { term: 'Persistente', definition: 'Que se guarda en disco y sobrevive a un reinicio de RabbitMQ. Todo lo que crea el laboratorio lo es.' },
  { term: 'Productor', definition: 'La aplicación (o la persona) que envía mensajes.' },
  { term: 'Rechazar y reencolar', definition: 'Devolver el mensaje a la cola para que se vuelva a entregar, marcado como reentregado.' },
  {
    term: 'Rechazar sin reencolar',
    definition: 'Rechazar el mensaje sin devolverlo a la cola: va a la dead-letter queue si la cola tiene una y, si no, se descarta.',
  },
  { term: 'Reentregado', definition: 'Un mensaje que ya se había entregado antes. Es una marca informativa de RabbitMQ.' },
  { term: 'Routing key', definition: 'La etiqueta que lleva cada mensaje y con la que se comparan los bindings.' },
  { term: 'Topic', definition: 'Tipo de exchange que entrega según un patrón con palabras separadas por puntos y los comodines «*» y «#».' },
].sort((a, b) => a.term.localeCompare(b.term, 'es'));

# Especificación 03 — Guía didáctica del Laboratorio RabbitMQ

- **Estado:** Aprobada
- **Fecha:** 2026-10-30

## 1. Contexto y objetivo

El [Laboratorio RabbitMQ](../02-rabbitmq-integration/spec.md) permite crear exchanges, colas y bindings, publicar y consumir mensajes. Pero quien no conoce RabbitMQ se encuentra con palabras como *exchange*, *binding* o *acknowledgement* sin saber qué son ni en qué orden usarlas.

**Objetivo:** añadir al laboratorio una **guía** escrita para quien parte de cero: explica con palabras sencillas, analogías y ejemplos cada elemento de RabbitMQ, y acompaña un **ejemplo paso a paso** para ver todo el flujo funcionando en el propio laboratorio.

**Dónde vive:** es una pestaña más del Laboratorio RabbitMQ, no una sección nueva del menú principal.

**Tono:** como si se lo explicaras a alguien que nunca ha oído hablar de mensajería. Frases cortas, nada de jerga sin explicar, y siempre un ejemplo concreto.

## 2. Usuarios

- **Persona que no sabe nada de RabbitMQ** y quiere entenderlo sin leer documentación técnica.
- **Persona que ya lo usa** y quiere recordar un concepto o repasar cómo se comportan los acknowledgements o las dead-letter queues.

## 3. Requisitos

### RF-1 — Acceso a la guía
- **CA-1.1:** El laboratorio deberá ofrecer una pestaña «Guía», junto a las demás áreas.
- **CA-1.2:** La guía deberá poder leerse **aunque RabbitMQ no esté disponible**, porque no necesita hablar con él.
- **CA-1.3:** La guía deberá tener un índice en la parte superior que lleve a cada sección.

### RF-2 — Los elementos, uno a uno
Para cada elemento de la lista, la guía deberá incluir, siempre en este orden y con estos cuatro títulos:
**«En una frase»**, **«Piénsalo así»** (una analogía de la vida real), **«Ejemplo»** y **«Dónde lo ves en el laboratorio»** (con un enlace a esa pantalla).

- **CA-2.1:** Elementos que se explicarán: mensaje, productor, exchange, cola, binding, routing key, consumidor, acknowledgement (confirmar, rechazar y reencolar, rechazar sin reencolar) y dead-letter queue.
- **CA-2.2:** Se explicará cada tipo de exchange (**direct**, **fanout** y **topic**) con un ejemplo propio y cuándo conviene usar cada uno, incluidos los comodines `*` y `#` del topic.
- **CA-2.3:** Se explicarán también las ideas que más confunden: el exchange por defecto, qué pasa con un mensaje que no llega a ninguna cola, qué es un mensaje «pendiente de confirmar» y por qué un mensaje puede aparecer como «reentregado».
- **CA-2.4:** Cada término técnico se explicará la primera vez que aparezca, y la guía incluirá un glosario breve con todos.

### RF-3 — El mapa del recorrido
- **CA-3.1:** La guía deberá incluir un diagrama del recorrido completo de un mensaje: productor → exchange → binding → cola → consumidor → confirmación o dead-letter queue.
- **CA-3.2:** Deberá incluir un diagrama sencillo para cada tipo de exchange que muestre a qué colas llega un mensaje y a cuáles no.
- **CA-3.3:** Todo diagrama tendrá una descripción en texto que cuente lo mismo, para quien no pueda verlo.

### RF-4 — Ejemplo paso a paso
- **CA-4.1:** La guía incluirá un ejemplo continuo (una pizzería que recibe pedidos de pizzas y bebidas) que se sigue en el propio laboratorio, de principio a fin.
- **CA-4.2:** Cada paso tendrá tres partes: **«Haz esto»** (con un enlace a la pantalla), **«Deberías ver»** (lo que aparecerá, para poder compararlo) y **«Qué ha pasado»** (la explicación).
- **CA-4.3:** El ejemplo cubrirá: crear un exchange, crear colas (una con dead-letter queue), crear los bindings, publicar un mensaje que llega, publicar uno que **no** llega a ninguna cola, consumir en modo manual, confirmar, rechazar y reencolar, rechazar hacia la dead-letter queue e inspeccionarla.
- **CA-4.4:** Incluirá una variante corta con un exchange fanout, para ver la diferencia con el topic.
- **CA-4.5:** Los nombres del ejemplo (exchanges, colas, routing keys) serán fijos y se repetirán igual en todos los pasos, para poder copiarlos.
- **CA-4.6:** Al final habrá un paso opcional para dejar el laboratorio limpio.

### RF-5 — Si algo no sale como esperas
- **CA-5.1:** La guía incluirá una sección con los despistes más habituales de quien empieza y su solución, entre ellos: «mi mensaje no llega a la cola», «el patrón del topic no coincide», «el consumidor no recibe nada», «el mensaje sigue ahí tras consumirlo» y «un mensaje aparece reentregado sin que lo haya rechazado».

## 4. Requisitos no funcionales

- **RNF-1 (Claridad):** frases cortas, voz activa y sin jerga sin explicar. Cada concepto tendrá un ejemplo.
- **RNF-2 (Accesibilidad):** títulos jerárquicos, navegable con teclado, contraste suficiente y diagramas con texto alternativo.
- **RNF-3 (Coherencia):** los nombres y textos de la guía coincidirán con los de las pantallas del laboratorio y del README.
- **RNF-4 (Independencia):** el contenido es estático; no llama al backend ni a RabbitMQ.
- **RNF-5 (Pantallas pequeñas):** se podrá leer en una pantalla estrecha sin desplazamiento horizontal.

## 5. Fuera de alcance

- Ejecutar los pasos automáticamente: el usuario los hace él en las pantallas del laboratorio.
- Contenido sobre Kafka o comparativas con RabbitMQ.
- Tipos de exchange, de cola o funciones que el laboratorio no ofrece (headers, quorum, TTL...). Se puede mencionar que existen, sin explicarlos.
- Traducciones a otros idiomas.

## 6. Criterios de finalización

1. La pestaña «Guía» existe, se lee sin RabbitMQ y tiene índice.
2. Cada elemento de CA-2.1 tiene sus cuatro títulos, y hay glosario.
3. Los diagramas de CA-3.1 y CA-3.2 existen y tienen descripción en texto.
4. Una persona que no conoce RabbitMQ completa el ejemplo de principio a fin en el laboratorio y obtiene lo que dice «Deberías ver» en cada paso.
5. Las pruebas automatizadas del frontend pasan, incluidas las de las especificaciones 01 y 02.
6. El README menciona la guía.

## 7. Decisiones tomadas

1. **Orden de las pestañas:** «Guía» va la primera, pero el laboratorio sigue abriendo en «Exchanges».
2. **El ejemplo no se ejecuta solo:** cada paso enlaza a la pantalla correspondiente y el usuario hace la operación.
3. **Tema del ejemplo:** una pizzería (exchange `pizzeria`, colas `cocina`, `barra` y `cocina-fallidos`).

# Especificación 04 — Generador de tráfico del Laboratorio RabbitMQ

- **Estado:** Aprobada
- **Fecha:** 2026-10-30

## 1. Contexto y objetivo

En el [Laboratorio RabbitMQ](../02-rabbitmq-integration/spec.md) cada mensaje se publica a mano, y pasa tan rápido que no da tiempo a verlo. En la vida real, robots, sensores y PLC envían datos sin parar.

**Objetivo:** añadir al laboratorio un **generador de tráfico**: emisores simulados que publican mensajes de forma continua, a la frecuencia que decida el usuario, hasta que los pare; y consumidores simulados que los procesan. Permite ver cómo se comportan las colas con un flujo constante, sin tener que pulsar nada.

**Dónde vive:** una pestaña más del Laboratorio RabbitMQ, «Generador». Los emisores y los consumidores simulados corren en el **backend**: siguen funcionando aunque se cambie de pestaña o se cierre el navegador, hasta que se paren.

**Relación con la especificación 02:** la 02 decidió que el estado vive solo en RabbitMQ. Esta añade una excepción acotada: la configuración de los emisores y consumidores simulados vive **en la memoria del backend** y se pierde al reiniciarlo. No se guarda en ningún otro sitio.

## 2. Usuarios

- **Persona que aprende o prueba RabbitMQ**, o que quiere reproducir el tráfico de una planta industrial (robots, sensores) para ver cómo reaccionan colas, exchanges y consumidores.

## 3. Requisitos

### RF-1 — Emisores simulados
- **CA-1.1:** El usuario podrá crear un emisor indicando: nombre, destino (un exchange con su routing key, o directamente una cola), frecuencia y plantilla del cuerpo.
- **CA-1.2:** La frecuencia será **fija** (por ejemplo, cada 500 ms) o **aleatoria dentro de un rango** (por ejemplo, entre 200 y 800 ms).
- **CA-1.3:** Opcionalmente, un límite: un número de mensajes o una duración, tras el cual el emisor se para solo.
- **CA-1.4:** Cada emisor se podrá iniciar, pausar, reanudar, parar y eliminar. Un emisor solo se puede editar mientras está parado.
- **CA-1.5:** Habrá una acción «Parar todo», siempre visible, que para todos los emisores y consumidores simulados.
- **CA-1.6:** Un emisor seguirá publicando hasta que se pare, aunque el usuario cambie de pestaña o cierre el navegador.
- **CA-1.7:** Si el destino deja de existir (exchange o cola eliminados), el emisor se parará e indicará el motivo en español.
- **CA-1.8:** Cada emisor mostrará su estado, cuántos mensajes ha publicado, cuántos no se han podido enrutar y su ritmo real en mensajes por segundo.

### RF-2 — Plantillas del cuerpo
- **CA-2.1:** El cuerpo de cada mensaje se generará a partir de una plantilla de texto (normalmente JSON) con **valores que cambian en cada envío**: número dentro de un rango, valor que deriva poco a poco (como una temperatura), onda senoidal, contador, fecha y hora, elección de una lista, verdadero/falso y varios ejes a la vez (por ejemplo, las seis articulaciones de un brazo robótico).
- **CA-2.2:** La plantilla **no ejecuta código**: solo admite los valores anteriores.
- **CA-2.3:** Antes de iniciar un emisor, el usuario podrá ver un **ejemplo** del mensaje que generaría.
- **CA-2.4:** Habrá plantillas listas para usar, que rellenan el formulario y se pueden ajustar: brazo robótico (posición de seis ejes y estado de seguridad), temperatura, sonómetro (decibelios), vibración, tag de PLC que solo emite cuando cambia, y alarma esporádica.
- **CA-2.5:** Una plantilla inválida se rechazará con un mensaje que explique qué falla y dónde, y un cuerpo generado nunca superará los 64 KB.

### RF-3 — Límite de seguridad
- **CA-3.1:** Habrá un límite global de **mensajes por segundo**, sumando todos los emisores. Por defecto, **100**, y el usuario podrá cambiarlo desde la pantalla, hasta un máximo absoluto de **500**.
- **CA-3.2:** No se podrá iniciar ni reanudar un emisor si la suma de las frecuencias superaría el límite, ni bajar el límite por debajo de lo que ya está en marcha. En ambos casos se explicará la causa.
- **CA-3.3:** Habrá un máximo de 20 emisores y 20 consumidores simulados a la vez.
- **CA-3.4:** Si una cola a la que publica un emisor supera los **10 000** mensajes listos, la pantalla avisará.

### RF-4 — Consumidores simulados
- **CA-4.1:** El usuario podrá crear un consumidor simulado sobre una cola, indicando el tiempo que tarda en procesar cada mensaje (fijo o dentro de un rango) y el porcentaje de mensajes que falla.
- **CA-4.2:** Un mensaje que falla se rechazará, a elección del usuario, **reencolándolo** o **sin reencolar** (a la dead-letter queue de la cola, si la tiene).
- **CA-4.3:** Se podrá iniciar, parar y eliminar cada consumidor simulado, y seguirá en marcha igual que los emisores.
- **CA-4.4:** Cada consumidor mostrará cuántos mensajes ha procesado, cuántos han fallado y su ritmo real.
- **CA-4.5:** Si la cola se elimina, el consumidor se parará e indicará el motivo.

### RF-5 — La pantalla «Generador»
- **CA-5.1:** La pestaña «Generador» mostrará los emisores y los consumidores simulados con su estado real, **también al abrirla de nuevo** o desde otro navegador, no solo los creados en esa sesión.
- **CA-5.2:** Mientras esté abierta, la pantalla se actualizará sola cada pocos segundos.
- **CA-5.3:** Tendrá el formulario de creación de emisores con las plantillas y la vista previa, el de consumidores simulados, el campo para el límite global y «Parar todo».
- **CA-5.4:** Seguirá las reglas del laboratorio: mensajes en español, operaciones deshabilitadas si RabbitMQ no está disponible, confirmación antes de eliminar y botones bloqueados mientras una operación está en curso.

## 4. Requisitos no funcionales

- **RNF-1 (Seguridad):** el límite global y los máximos protegen a RabbitMQ de llenarse sin querer; el generador no ejecuta código del usuario ni guarda credenciales.
- **RNF-2 (Precisión):** un emisor fijo mantendrá su frecuencia con una desviación media inferior al 20 % mientras no se alcance el límite del sistema.
- **RNF-3 (Limpieza):** al apagar el backend, todos los emisores y consumidores simulados se detienen.

## 5. Fuera de alcance

- Guardar emisores o consumidores para que sobrevivan a un reinicio del backend.
- Emitir por MQTT, OPC UA u otros protocolos, o reproducir datos reales grabados.
- Kafka (llegará con su laboratorio).
- Gráficos en vivo y trazado real de RabbitMQ: son del visualizador de flujo (especificación 05).

## 6. Criterios de finalización

1. Un emisor fijo y uno con rango publican a su frecuencia hasta que se paran, y siguen al cambiar de pestaña.
2. Las plantillas generan los valores indicados, la vista previa los muestra y una plantilla inválida se rechaza.
3. El límite global se respeta y se puede cambiar desde la pantalla, con su máximo absoluto.
4. Un consumidor simulado procesa los mensajes, y los que fallan van a reencolarse o a la dead-letter queue según lo elegido.
5. Un emisor más rápido que un consumidor simulado hace crecer su cola, y se ve en «Colas».
6. «Parar todo» detiene todo, y al reiniciar el backend no queda nada en marcha.
7. Las pruebas del backend (incluidas las de integración) y del frontend pasan, con las de las especificaciones 01 a 03.
8. El README documenta el generador.

## 7. Decisiones tomadas

1. Los emisores y consumidores simulados corren en el backend y se pierden al reiniciarlo.
2. Se incluyen los consumidores simulados desde la primera versión.
3. Límite global por defecto de 100 mensajes por segundo, editable desde la pantalla hasta un máximo absoluto de 500.
4. Las plantillas industriales son genéricas (ejes, temperatura, decibelios...) porque no hay formatos reales de referencia; se ajustan desde el formulario.

# Tareas 04 — Generador de tráfico del Laboratorio RabbitMQ

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Una tarea solo se marca cuando su comprobación «Hecho cuando» se ha ejecutado y verificado. Las pruebas de cada tarea forman parte de la tarea. `./mvnw test` no necesita Docker; las `*IT` sí (`./mvnw verify`).

- [x] **T01 — Contrato y plantillas**
  Añadir al contrato las operaciones del generador y crear el analizador de plantillas con sus valores y las plantillas industriales.
  *RF:* RF-2
  *Hecho cuando:* `./mvnw compile` genera la interfaz, y las pruebas unitarias cubren cada valor (rango, deriva, senoidal, contador, hora, elección, booleano y ejes), los errores con su explicación, el límite de 64 KB y que cada plantilla industrial genera un cuerpo válido.

- [x] **T02 — Núcleo de emisores y límite de seguridad**
  Crear el núcleo, independiente de RabbitMQ: el servicio de emisores (frecuencia fija o en rango, límite de mensajes o de duración, pausar, reanudar, parar), el del límite global con sus máximos y la interfaz de publicación. Crear también el adaptador de RabbitMQ (destino exchange con routing key o cola) que la implementa.
  *RF:* RF-1, RF-3
  *Hecho cuando:* una comprobación (como la de capas de la 02) confirma que el núcleo no importa nada de RabbitMQ, y las pruebas unitarias, con publicador y reloj falsos, cubren la frecuencia, los contadores, la parada automática, el destino eliminado, el rechazo de iniciar o reanudar por superar el límite, el cambio del límite y los máximos de 20 y 500.

- [x] **T03 — Consumidores simulados**
  Crear el servicio de consumidores simulados con tiempo de proceso, porcentaje de fallos y acción ante el fallo.
  *RF:* RF-4
  *Hecho cuando:* las pruebas unitarias, con consumidores y reloj falsos, cubren el procesamiento, los fallos con reencolar y sin reencolar, los contadores, la cola eliminada y el máximo de 20.

- [x] **T04 — Controladores**
  Implementar los controladores del generador, incluidos «parar todo» y el cierre al apagar el backend.
  *RF:* RF-1 a RF-4
  *Hecho cuando:* las pruebas `@WebMvcTest` verifican cada operación, los errores en español y que el estado devuelto es el real al volver a consultarlo.

- [x] **T05 — Integración con RabbitMQ real**
  Escribir `GeneratorIT` contra un RabbitMQ real.
  *RF:* RF-1, RF-3, RF-4
  *Hecho cuando:* `./mvnw verify` pasa comprobando que un emisor publica a su frecuencia hasta que se para, que se respeta el límite global, que un emisor rápido hace crecer la cola de un consumidor lento y que un consumidor simulado con fallos manda mensajes a la dead-letter queue.

- [x] **T06 — Frontend: pestaña y emisores**
  Crear el servicio del generador, la pestaña «Generador» con el refresco periódico, la lista de emisores como componente compartido (`shared/generator/`) con sus controles, «Parar todo» y el límite global.
  *RF:* RF-1, RF-3, RF-5
  *Hecho cuando:* las pruebas del frontend verifican la pestaña y su refresco, el estado y contadores de cada emisor, iniciar, pausar, reanudar, parar y eliminar con confirmación, «Parar todo», el cambio de límite y sus errores. `npm test -- --watch=false` y `npx ng build` pasan.

- [x] **T07 — Frontend: formulario, plantillas y consumidores**
  Crear el formulario de emisores con plantillas, vista previa y validaciones (compartido, con un hueco para el destino y el formulario de destino de RabbitMQ aparte), y la sección de consumidores simulados. Incluir el aviso de cola con más de 10 000 mensajes.
  *RF:* RF-1, RF-2, RF-4, RF-5
  *Hecho cuando:* las pruebas verifican el formulario con las dos formas de frecuencia, que una plantilla del catálogo rellena el formulario, la vista previa, el rechazo de una plantilla inválida, el alta y la baja de consumidores simulados y el aviso de cola. `npm test -- --watch=false` y `npx ng build` pasan.

- [x] **T08 — README y verificación**
  Documentar el generador en el README y comprobar el conjunto.
  *RF:* todos
  *Hecho cuando:* el README lo documenta; `./mvnw test`, `./mvnw verify`, `npm test -- --watch=false` y `npx ng build` pasan con las pruebas de las especificaciones 01 a 03 incluidas; el asistente comprueba por HTTP los criterios de finalización 1 a 6; y el usuario lo prueba en Chrome.

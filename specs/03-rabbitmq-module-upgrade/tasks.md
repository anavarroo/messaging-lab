# Tareas 03 — Guía didáctica del Laboratorio RabbitMQ

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Referencias:** [spec.md](spec.md) · [plan.md](plan.md)

Una tarea solo se marca cuando su comprobación «Hecho cuando» se ha ejecutado y verificado. Las pruebas de cada tarea forman parte de la tarea.

- [x] **T01 — Ruta, pestaña y lectura sin RabbitMQ**
  Crear la ruta `guide` y la pestaña «Guía» (la primera), con una página inicial que ya tenga el índice. Hacer que la guía se vea con RabbitMQ no disponible, sin tocar el comportamiento de las demás áreas.
  *RF:* RF-1
  *Hecho cuando:* las pruebas verifican que la pestaña es la primera y navega a la guía, que con RabbitMQ no disponible la guía se muestra y su pestaña sigue habilitada (las demás, no), que no hace llamadas al backend, y que `/rabbitmq` sigue abriendo en «Exchanges». `npm test -- --watch=false` y `npx ng build` pasan.

- [x] **T02 — Los elementos, los diagramas y el glosario**
  Escribir los elementos de CA-2.1 con sus cuatro títulos, los tipos de exchange con sus comodines, las ideas que confunden (CA-2.3), los diagramas con descripción en texto, «Si algo no sale como esperas» y el glosario.
  *RF:* RF-2, RF-3, RF-5
  *Hecho cuando:* las pruebas verifican, para cada elemento, los cuatro títulos en orden y un enlace a su pantalla; que cada diagrama tiene título y descripción; que el índice enlaza a todas las secciones; y que el glosario incluye todos los términos de CA-2.1. `npm test -- --watch=false` pasa.

- [x] **T03 — Ejemplo paso a paso de la pizzería**
  Escribir el ejemplo de RF-4: los pasos de CA-4.3 con «Haz esto», «Deberías ver» y «Qué ha pasado», la variante con fanout y el paso final de limpieza.
  *RF:* RF-4
  *Hecho cuando:* las pruebas verifican que cada paso tiene sus tres partes, que los enlaces apuntan a rutas existentes, que los nombres del ejemplo son los mismos en todos los pasos, y que el ejemplo cubre todo lo de CA-4.3. Además, el asistente sigue el ejemplo contra el laboratorio real (por HTTP) y comprueba que cada «Deberías ver» es cierto.

- [ ] **T04 — README y verificación**
  Mencionar la guía en el README y comprobar el conjunto.
  *RF:* todos
  *Hecho cuando:* el README menciona la guía; `cd frontend && npm test -- --watch=false` y `npx ng build` pasan, con las pruebas de las especificaciones 01 y 02 incluidas; y una persona que no conoce RabbitMQ completa el ejemplo en Chrome y confirma que lo entiende (criterio de finalización 4).

# Plan 03 — Guía didáctica del Laboratorio RabbitMQ

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Especificación:** [spec.md](spec.md)

Solo frontend, sin dependencias nuevas, sin cambios de contrato ni de backend.

## Cómo se construye

- **Dónde:** `frontend/src/app/features/rabbitmq/guide/`, con dos componentes:
  - `guide-page`: índice, los elementos de RF-2, los diagramas, «Si algo no sale como esperas» y el glosario.
  - `guide-example`: el ejemplo paso a paso de RF-4, incrustado en la misma página.
- **Contenido:** escrito directamente en las plantillas HTML, en español, sin cargar nada del backend. Los diagramas son SVG en línea, con un `<title>` y una descripción en texto cada uno.
- **Enlaces a las pantallas:** `routerLink` relativos a `/rabbitmq/exchanges`, `queues`, `bindings`, `publish` y `consumers`.
- **Ruta y pestaña:** nueva ruta hija `guide` de `/rabbitmq` y pestaña «Guía» como primera. `/rabbitmq` sigue redirigiendo a `exchanges`.
- **Leerla sin RabbitMQ (CA-1.2):** hoy el contenedor `RabbitMqLab` no muestra ninguna área hasta que se conecta por primera vez y deshabilita las pestañas si no hay conexión. Cambia solo en la guía: su pestaña siempre está habilitada y su contenido se muestra siempre, fuera del `<fieldset>` que deshabilita las operaciones. El resto de áreas se comporta igual.

## Pruebas (sin backend ni RabbitMQ)

- La pestaña existe, es la primera y navega a la guía; la guía se muestra con RabbitMQ no disponible y sin llamar al backend.
- El índice tiene un enlace por sección y todos apuntan a una sección existente.
- Cada elemento de CA-2.1 tiene sus cuatro títulos en el orden indicado.
- Cada diagrama tiene título y descripción.
- Todos los pasos del ejemplo tienen «Haz esto», «Deberías ver» y «Qué ha pasado», y sus enlaces apuntan a rutas que existen.
- Los nombres del ejemplo coinciden en todos los pasos.
- Regresión: pasan las pruebas de las especificaciones 01 y 02.

## Riesgos

- **Que el texto no sea tan claro como se pretende.** Se mitiga con la verificación de una persona que no conozca RabbitMQ (criterio de finalización 4).
- **Que el ejemplo se desfase de las pantallas** si estas cambian. Se mitiga con la prueba de que los enlaces apuntan a rutas existentes y con RNF-3.

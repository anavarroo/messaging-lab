# Constitution — Messaging Lab

## Principios innegociables

1. **Simplicidad del stack:** utilizar Java 21, Spring Boot, Angular, RabbitMQ, Kafka y Docker Compose; añadir dependencias solo con justificación y aprobación.
2. **Spec-Driven Development:** toda funcionalidad debe estar definida y aprobada en `specs/` antes de implementarse; el código debe cumplir sus criterios de aceptación.
3. **Separación de responsabilidades:** mantener la lógica de negocio independiente de la interfaz Angular y de los detalles de infraestructura y mensajería.
4. **Tests obligatorios:** toda funcionalidad nueva debe incluir pruebas automatizadas; ejecutar las pruebas pertinentes antes de dar una tarea por terminada.
5. **Persistencia explícita:** persistir únicamente los datos necesarios mediante tecnologías aprobadas; no introducir almacenamiento ni modificar esquemas sin especificación previa.
6. **Idioma coherente:** utilizar inglés para identificadores del código y español para documentación, especificaciones y mensajes dirigidos al usuario.


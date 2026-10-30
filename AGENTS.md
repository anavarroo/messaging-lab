# AGENTS.md — messaging-lab

## Proyecto

Laboratorio visual para aprender, experimentar y comparar sistemas de mensajería con RabbitMQ y Apache Kafka.

El proyecto se desarrolla en un único repositorio Git (monorepo) que contiene el backend, el frontend, la infraestructura, las especificaciones, la documentación y las instrucciones para los agentes de IA.

El backend utiliza Java 21 y Spring Boot. El frontend utiliza Angular 21. La aplicación permite publicar y consumir mensajes, visualizar eventos y explorar conceptos como colas, tópicos, productores, consumidores y grupos de consumidores.

El contrato OpenAPI de la API REST se encuentra en `backend`. La infraestructura local se gestiona mediante Docker Compose.

## Estructura del repositorio

- `backend/`: aplicación Spring Boot, pruebas y contrato OpenAPI.
- `frontend/`: aplicación Angular, componentes, servicios y pruebas.
- `infra/`: Docker Compose y configuración de los servicios de infraestructura.
- `specs/`: especificaciones organizadas por funcionalidad o hito.
- `docs/`: constitución y documentación general del proyecto.
- `AGENTS.md`: instrucciones generales para los agentes de IA.
- `README.md`: presentación del proyecto e instrucciones de uso.

Cada especificación debe residir en `specs/NN-nombre-descriptivo/` y contener:
- `spec.md`: requisitos y criterios de aceptación.
- `plan.md`: diseño y estrategia de implementación.
- `tasks.md`: tareas de implementación verificables.

Mantén las responsabilidades de cada directorio y evita duplicar documentación o configuraciones entre módulos.

## Comandos

- Backend: `cd backend && ./mvnw spring-boot:run`
- Tests backend: `cd backend && ./mvnw test`
- Frontend: `cd frontend && npm start`
- Tests frontend: `cd frontend && npm test`
- Infraestructura: `docker compose -f infra/docker-compose.yml up -d --build`
- Detener infraestructura: `docker compose -f infra/docker-compose.yml down`

Ejecuta únicamente los comandos correspondientes a los módulos existentes y a las herramientas realmente configuradas. Si un comando no está disponible o requiere una configuración previa, indícalo sin asumir que ha funcionado.

## Estilo

- Java 21 y Spring Boot para el backend.
- Angular 21 para el frontend, respetando las versiones fijadas en los archivos de configuración.
- RabbitMQ y Apache Kafka como sistemas de mensajería.
- Docker Compose para ejecutar los servicios de infraestructura en local.
- OpenAPI como contrato de referencia de la API REST.
- Identificadores de código en inglés; documentación, especificaciones y mensajes dirigidos al usuario en español.
- Código modular, legible, mantenible y testeable.
- Prioriza las pruebas automatizadas y evita dependencias innecesarias.

## Reglas

- Lee `docs/constitution.md` y la especificación activa en `specs/` antes de implementar cambios.
- Trata las especificaciones aprobadas como fuente de verdad del proyecto.
- No implementes funcionalidades que no estén contempladas en una especificación aprobada.
- Respeta el orden de trabajo: especificación, plan, tareas, implementación y validación.
- No modifiques archivos de `specs/` salvo petición explícita o autorización dentro del flujo de trabajo acordado.
- No añadas dependencias, cambies contratos de API ni alteres decisiones arquitectónicas aprobadas sin justificar el cambio y obtener la aprobación necesaria.
- Si una tarea afecta a varios módulos, identifica y valida todos los componentes afectados.
- Mantén separados el frontend, el backend y la infraestructura.
- Evita acoplar la lógica de negocio a RabbitMQ o Kafka; utiliza adaptadores o abstracciones cuando lo establezca la arquitectura.
- Mantén sincronizado `backend/api/api.yml` con la implementación de la API y valida los cambios de contrato.
- No introduzcas credenciales, secretos ni configuraciones locales sensibles en el repositorio.
- No afirmes que una prueba o validación ha pasado si no se ha ejecutado y comprobado su resultado.

## Al terminar cualquier tarea

- Ejecuta las pruebas pertinentes del módulo afectado y, cuando sea viable, las pruebas de regresión del proyecto.
- Comprueba que los cambios respetan las especificaciones, la constitución y el contrato OpenAPI.
- Verifica todos los módulos afectados si el cambio cruza los límites del backend, frontend o infraestructura.
- Resume los cambios realizados, las pruebas ejecutadas y sus resultados.
- Si alguna prueba no se ha podido ejecutar, explica el motivo.
- Si detectas que una especificación debe actualizarse, indícalo explícitamente.
- No des por terminada una tarea si quedan errores conocidos sin documentar.
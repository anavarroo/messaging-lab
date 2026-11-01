# Especificación 01 — Base del proyecto

- **Estado:** Aprobada
- **Fecha:** 2026-10-29

## 1. Contexto y objetivo

messaging-lab es un laboratorio visual para aprender, experimentar y comparar sistemas de mensajería. Antes de poder publicar, consumir o visualizar mensajes, el proyecto necesita una base mínima que funcione de extremo a extremo: un servicio de backend, una interfaz web que se comunique con él y una forma sencilla y reproducible de ponerlo todo en marcha.

**Objetivo:** que cualquier persona que clone el repositorio pueda arrancar la aplicación siguiendo unas instrucciones documentadas y comprobar, desde la interfaz web, que esta se comunica correctamente con el backend.

**Por qué:** las funcionalidades posteriores (integración con brokers de mensajería, publicación, consumo y visualización) se apoyarán en esta base. Validar primero la comunicación interfaz ↔ backend, el arranque reproducible y la existencia de pruebas automatizadas reduce el riesgo de las siguientes especificaciones y fija desde el inicio el flujo de trabajo exigido por la constitución.

## 2. Usuarios

- **Persona desarrolladora del proyecto:** clona el repositorio, arranca la aplicación en local para trabajar en ella y ejecuta las pruebas.
- **Persona que aprende o evalúa el laboratorio:** quiere poner la aplicación en marcha con el menor esfuerzo posible y comprobar que funciona, sin necesidad de conocer los detalles internos.

## 3. Historias de usuario

- **HU-1:** Como persona desarrolladora, quiero arrancar el backend y la interfaz por separado en mi máquina, para poder desarrollar y depurar cada parte de forma independiente.
- **HU-2:** Como persona que evalúa el laboratorio, quiero levantar la aplicación completa con un único comando, para no tener que preparar mi entorno pieza a pieza.
- **HU-3:** Como usuario de la interfaz, quiero ver si el backend está disponible junto con su nombre y versión, para saber que la aplicación funciona y qué versión estoy usando.
- **HU-4:** Como usuario de la interfaz, quiero recibir un mensaje claro y poder reintentar cuando el backend no responde, para entender el problema y recuperarme sin recargar la página.
- **HU-5:** Como usuario de la interfaz, quiero ver la estructura de navegación general del laboratorio, para hacerme una idea de las secciones que estarán disponibles en el futuro.
- **HU-6:** Como persona desarrolladora, quiero disponer de pruebas automatizadas y de instrucciones de arranque en español, para verificar que la base funciona y poder incorporarme al proyecto sin ayuda.

## 4. Requisitos funcionales

### RF-1 — Información de estado del backend
El backend debe ofrecer una operación de consulta que devuelva su estado, el nombre de la aplicación y su versión.

Criterios de aceptación (EARS):
- **CA-1.1:** Cuando se consulte la operación de estado del backend, el sistema deberá responder indicando el estado «disponible», el nombre de la aplicación y su versión.
- **CA-1.2:** El sistema deberá obtener la versión informada de la misma fuente que identifica la versión del backend, sin duplicarla manualmente.
- **CA-1.3:** El sistema deberá documentar la operación de estado en el contrato de la API.
- **CA-1.4:** El sistema deberá informar la misma versión tanto en el arranque local de desarrollo como en el arranque completo, sin sufijos ni distinciones entre ambos.

### RF-2 — Visualización del estado en la interfaz
La interfaz debe consultar el estado del backend y mostrarlo al usuario.

Criterios de aceptación (EARS):
- **CA-2.1:** Cuando el usuario abra la interfaz, el sistema deberá consultar el estado del backend.
- **CA-2.2:** Mientras la consulta de estado esté en curso, el sistema deberá mostrar un indicador de carga.
- **CA-2.3:** Cuando el backend responda correctamente, el sistema deberá mostrar el estado «disponible», el nombre de la aplicación y la versión del backend.

### RF-3 — Gestión del error de comunicación
La interfaz debe informar al usuario cuando no pueda comunicarse con el backend y permitirle reintentar.

Criterios de aceptación (EARS):
- **CA-3.1:** Si el backend no responde dentro del tiempo máximo de espera, entonces el sistema deberá mostrar un mensaje en español indicando que el backend no está disponible.
- **CA-3.2:** Si el backend responde con un error o con una respuesta no válida, entonces el sistema deberá mostrar un mensaje en español indicando que no se ha podido obtener el estado del backend.
- **CA-3.3:** Mientras se muestre un mensaje de error de comunicación, el sistema deberá ofrecer una acción para reintentar la consulta.
- **CA-3.4:** Cuando el usuario active la acción de reintentar, el sistema deberá repetir la consulta de estado sin recargar la página.
- **CA-3.5:** Cuando un reintento tenga éxito, el sistema deberá sustituir el mensaje de error por la información de estado del backend.
- **CA-3.6:** Mientras un reintento esté en curso, el sistema deberá impedir que el usuario lance un reintento adicional.

### RF-4 — Esqueleto de navegación
La interfaz debe presentar la estructura visual base del laboratorio.

Criterios de aceptación (EARS):
- **CA-4.1:** El sistema deberá mostrar una cabecera con el nombre del laboratorio en todas las vistas.
- **CA-4.2:** El sistema deberá mostrar un menú de navegación con la sección de estado activa y las siguientes secciones futuras visibles pero deshabilitadas:
  - **Laboratorio RabbitMQ:** colas, exchanges, bindings, routing keys, publicación, acknowledgements y dead-letter queues.
  - **Laboratorio Kafka:** topics, particiones, offsets, productores, consumer groups y lag.
  - **Visualizador de flujo:** publicar un mensaje y seguir visualmente su recorrido, las entregas y los errores.
- **CA-4.5:** El sistema deberá mostrar junto a cada sección deshabilitada su descripción breve, tal como figura en CA-4.2.
- **CA-4.3:** Cuando el usuario intente acceder a una sección deshabilitada, el sistema no deberá navegar a ella y deberá indicar que estará disponible próximamente.
- **CA-4.4:** Si el usuario accede a una dirección de la interfaz que no existe, entonces el sistema deberá redirigirle a la sección de estado.

### RF-5 — Arranque local por componentes
Cada componente (backend e interfaz) debe poder arrancarse en local de forma independiente.

Criterios de aceptación (EARS):
- **CA-5.1:** Cuando la persona desarrolladora ejecute el comando documentado de arranque del backend, el sistema deberá dejar el backend aceptando consultas de estado.
- **CA-5.2:** Cuando la persona desarrolladora ejecute el comando documentado de arranque de la interfaz, el sistema deberá servir la interfaz accesible desde el navegador.
- **CA-5.3:** Mientras ambos componentes estén arrancados en local, el sistema deberá permitir que la interfaz consulte el estado del backend sin configuración manual adicional.
- **CA-5.4:** Donde la persona desarrolladora indique puertos distintos de los predeterminados mediante configuración externa al repositorio, el sistema deberá arrancar cada componente en el puerto indicado sin modificar ningún archivo versionado, manteniendo la comunicación entre la interfaz y el backend.

### RF-6 — Arranque completo con un único comando
La aplicación completa debe poder levantarse en contenedores con un único comando.

Criterios de aceptación (EARS):
- **CA-6.1:** Cuando se ejecute el comando documentado de arranque completo, el sistema deberá levantar el backend y la interfaz.
- **CA-6.2:** Cuando el arranque completo finalice, el sistema deberá permitir abrir la interfaz desde el navegador y ver el estado del backend como «disponible».
- **CA-6.3:** Cuando se ejecute el comando documentado de parada, el sistema deberá detener todos los componentes levantados por el arranque completo.
- **CA-6.4:** Si el backend todavía no está listo cuando la interfaz ya es accesible, entonces el sistema deberá comportarse según RF-3, permitiendo reintentar hasta que el backend esté disponible.
- **CA-6.5:** Donde se indiquen puertos distintos de los predeterminados mediante configuración externa al repositorio, el sistema deberá publicar los componentes del arranque completo en esos puertos sin modificar ningún archivo versionado.

### RF-7 — Pruebas automatizadas mínimas
La base debe incluir pruebas automatizadas que verifiquen la comprobación de estado en ambos componentes.

Criterios de aceptación (EARS):
- **CA-7.1:** Cuando se ejecuten las pruebas del backend, el sistema deberá verificar que la operación de estado devuelve el estado, el nombre y la versión esperados.
- **CA-7.2:** Cuando se ejecuten las pruebas de la interfaz, el sistema deberá verificar la visualización del estado cuando el backend responde correctamente.
- **CA-7.3:** Cuando se ejecuten las pruebas de la interfaz, el sistema deberá verificar la visualización del mensaje de error y el funcionamiento del reintento cuando el backend falla.
- **CA-7.4:** Las pruebas de ambos componentes deberán poder ejecutarse sin necesidad de que el otro componente esté en marcha.

### RF-8 — Documentación de arranque
El repositorio debe incluir instrucciones de arranque en español.

Criterios de aceptación (EARS):
- **CA-8.1:** El sistema deberá documentar en español los requisitos previos necesarios para arrancar el proyecto.
- **CA-8.2:** El sistema deberá documentar los pasos para el arranque local por componentes y para el arranque completo con un único comando.
- **CA-8.3:** El sistema deberá documentar cómo ejecutar las pruebas de cada componente.
- **CA-8.4:** El sistema deberá documentar cómo comprobar que la aplicación funciona y cómo detenerla.
- **CA-8.5:** El sistema deberá documentar los puertos predeterminados de cada componente y cómo cambiarlos sin modificar archivos versionados.

## 5. Requisitos no funcionales

- **RNF-1 (Idioma):** los textos visibles para el usuario y la documentación estarán en español; los identificadores de código, en inglés.
- **RNF-2 (Reproducibilidad):** una persona con los requisitos previos documentados instalados deberá poder arrancar la aplicación siguiendo únicamente la documentación, sin pasos no descritos.
- **RNF-3 (Tiempo de respuesta):** la operación de estado deberá responder en menos de 1 segundo en un entorno local con el backend en marcha.
- **RNF-4 (Tiempo de espera):** la interfaz deberá dejar de esperar la respuesta del backend tras 20 segundos sin respuesta, para mostrar el error en lugar de quedarse cargando indefinidamente.
- **RNF-5 (Seguridad):** el repositorio no contendrá credenciales, secretos ni configuración local sensible.
- **RNF-6 (Mínimas dependencias):** no se añadirán dependencias que no sean necesarias para cumplir esta especificación.
- **RNF-7 (Separación):** backend, interfaz e infraestructura se mantendrán como módulos independientes, sin que uno dependa del código interno de otro.
- **RNF-8 (Accesibilidad básica):** los mensajes de estado y error deberán ser perceptibles por tecnologías de asistencia y la acción de reintentar deberá poder activarse con el teclado.
- **RNF-9 (Compatibilidad):** la interfaz se verificará en un único navegador de escritorio: la última versión estable de Google Chrome, el navegador más utilizado.

## 6. Casos límite

- **CL-1:** El backend está apagado al abrir la interfaz → se muestra el mensaje de no disponibilidad con la acción de reintentar (RF-3).
- **CL-2:** El backend arranca más tarde que la interfaz (habitual en el arranque completo) → el primer intento puede fallar; un reintento posterior debe mostrar el estado correctamente (CA-6.4).
- **CL-3:** El backend responde con un error del servidor o con un contenido incompleto (por ejemplo, sin versión) → se trata como respuesta no válida (CA-3.2).
- **CL-4:** El backend tarda más de 20 segundos en responder → se muestra el error en lugar de un indicador de carga indefinido (CA-3.1).
- **CL-5:** El usuario pulsa reintentar varias veces seguidas → solo hay una consulta en curso a la vez (CA-3.6).
- **CL-6:** El backend se cae después de haber mostrado el estado «disponible» → en esta funcionalidad no se detecta automáticamente; la información mostrada corresponde a la última consulta. (Ver fuera de alcance: monitorización continua.)
- **CL-7:** Un puerto necesario ya está ocupado en la máquina → el arranque falla con el error propio del componente; la persona puede elegir otro puerto mediante configuración externa al repositorio (CA-5.4, CA-6.5), siguiendo la documentación (CA-8.5).
- **CL-9:** Se cambia el puerto del backend pero no se ajusta la interfaz → la interfaz no logra comunicarse y muestra el error de RF-3; la documentación deberá indicar qué hay que ajustar para mantener la comunicación.
- **CL-8:** El usuario navega a una dirección inexistente de la interfaz → se redirige a la sección de estado (CA-4.4).

## 7. Fuera de alcance

- Infraestructura, conexión o comprobación de estado de RabbitMQ y Kafka (se abordarán en sus propias especificaciones).
- Publicación, consumo y visualización de mensajes, colas, tópicos o grupos de consumidores.
- Contenido funcional de las secciones futuras del menú (solo aparecen deshabilitadas).
- Monitorización continua o reintentos automáticos del estado del backend.
- Autenticación, autorización y gestión de usuarios.
- Persistencia de datos.
- Integración continua y despliegue en entornos distintos del local.
- Internacionalización a idiomas distintos del español.
- Verificación en navegadores distintos de Google Chrome o en dispositivos móviles.
- Observabilidad avanzada (métricas, trazas, paneles).

## 8. Criterios de finalización

La funcionalidad se considera terminada cuando:

1. Se cumplen todos los criterios de aceptación de RF-1 a RF-8.
2. La aplicación arranca correctamente en las dos modalidades (local por componentes y completa con un único comando) y, en ambas, la interfaz muestra el estado «disponible», el nombre y la versión del backend.
3. Con el backend detenido, la interfaz muestra el mensaje de error y, tras arrancarlo, el reintento muestra el estado correctamente.
4. Las pruebas automatizadas del backend y de la interfaz se han ejecutado y pasan, y su resultado ha sido comprobado.
5. El contrato de la API refleja la operación de estado y es coherente con la implementación.
6. La documentación de arranque se ha seguido desde un clon limpio del repositorio sin necesidad de pasos adicionales.
7. No hay errores conocidos sin documentar.

## 9. Dudas abiertas

No quedan dudas abiertas. Todas las aclaraciones se han incorporado a la especificación (RNF-4, RNF-9, CA-1.4, CA-4.2, CA-4.5, CA-5.4, CA-6.5, CA-8.5, CL-7 y CL-9).

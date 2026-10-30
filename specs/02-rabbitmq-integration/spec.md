# Especificación 02 — Integración con RabbitMQ

- **Estado:** Aprobada
- **Fecha:** 2026-10-30

## 1. Contexto y objetivo

messaging-lab es un laboratorio visual para aprender, experimentar y comparar sistemas de mensajería. La [especificación 01](../01-project-foundation/spec.md) dejó una base funcional: una interfaz web que se comunica con el backend y un arranque reproducible. En el menú, la sección «Laboratorio RabbitMQ» aparece deshabilitada como «Disponible próximamente».

Esta especificación es la primera que incorpora un sistema de mensajería real. Convierte esa sección en un laboratorio interactivo de RabbitMQ con el modelo de enrutado completo: exchanges, colas, bindings, routing keys, publicación, acknowledgements y dead-letter queues.

**Objetivo:** que una persona pueda, desde la interfaz web, construir una topología de RabbitMQ (exchanges, colas y bindings), publicar mensajes, consumirlos de forma interactiva decidiendo qué hacer con cada uno (confirmar, rechazar y reencolar, o rechazar sin reencolar), observar el estado de las colas y de sus mensajes, y comprobar qué ocurre con los mensajes rechazados cuando existe una dead-letter queue. La topología y los mensajes se conservan entre reinicios de RabbitMQ.

**Por qué:**
- Entender el enrutado de RabbitMQ requiere experimentar: un laboratorio donde el usuario monta y modifica la topología enseña más que una topología fija.
- Es la base de las siguientes especificaciones: el laboratorio de Kafka, que se comparará con este, y el visualizador de flujo, que mostrará el recorrido de los mensajes.
- La constitución exige decidir de forma explícita qué datos se persisten. Esta especificación lo decide: el estado vive únicamente en RabbitMQ y la aplicación no añade ningún otro almacenamiento.

**Relación con la especificación 01:** esta especificación **cambia el comportamiento de CA-4.2 y CA-4.3 de la 01 para la sección «Laboratorio RabbitMQ»**, que pasa de estar deshabilitada a estar activa. Las secciones «Laboratorio Kafka» y «Visualizador de flujo» siguen igual. En consecuencia, las pruebas de la 01 que verifican la lista de secciones deshabilitadas deberán actualizarse (ver CA-12.6).

## 2. Usuarios

- **Persona que aprende RabbitMQ:** construye topologías, publica y consume mensajes, y observa el comportamiento del enrutado, las confirmaciones y las dead-letter queues, sin necesidad de escribir código ni de usar herramientas externas.
- **Persona desarrolladora del proyecto:** levanta RabbitMQ junto con la aplicación, configura puertos y credenciales en su entorno, ejecuta las pruebas y reinicia o limpia el laboratorio.

## 3. Historias de usuario

- **HU-1:** Como persona desarrolladora, quiero levantar RabbitMQ junto con el resto de la aplicación, o por separado para el desarrollo local, para poder trabajar sin preparar nada a mano.
- **HU-2:** Como usuario, quiero ver si la aplicación está conectada a RabbitMQ, para saber si el laboratorio puede usarse y qué hacer cuando no.
- **HU-3:** Como usuario, quiero crear y eliminar exchanges de tipo direct, fanout y topic, para experimentar con cada estrategia de enrutado.
- **HU-4:** Como usuario, quiero crear, vaciar y eliminar colas, y poder asociar a una cola una dead-letter queue, para ver qué ocurre con los mensajes que no se pueden procesar.
- **HU-5:** Como usuario, quiero crear y eliminar bindings con su routing key o patrón, para decidir qué mensajes llegan a cada cola.
- **HU-6:** Como usuario, quiero publicar un mensaje en un exchange con una routing key y saber si ha llegado a alguna cola, para comprobar que el enrutado hace lo que espero.
- **HU-7:** Como usuario, quiero ver cuántos mensajes tiene cada cola y poder inspeccionar su contenido sin consumirlo, para entender el estado del sistema sin alterarlo.
- **HU-8:** Como usuario, quiero iniciar un consumidor sobre una cola y decidir mensaje a mensaje si lo confirmo, lo rechazo y lo reencolo, o lo rechazo sin reencolar, para entender los acknowledgements y las dead-letter queues.
- **HU-9:** Como usuario, quiero que mi topología y mis mensajes sigan ahí tras reiniciar RabbitMQ, y poder empezar de cero cuando lo desee, para continuar un experimento otro día o repetirlo limpio.
- **HU-10:** Como persona desarrolladora, quiero pruebas automatizadas, un contrato de API actualizado e instrucciones en español, para verificar y mantener la integración.

## 4. Requisitos funcionales

### RF-1 — RabbitMQ en la infraestructura local
RabbitMQ debe formar parte de la infraestructura local del proyecto, tanto en el arranque completo como en el arranque local por componentes.

Criterios de aceptación (EARS):
- **CA-1.1:** Cuando se ejecute el comando documentado de arranque completo, el sistema deberá levantar RabbitMQ junto con el backend y la interfaz.
- **CA-1.2:** Cuando se ejecute el comando documentado para levantar solo RabbitMQ, el sistema deberá dejarlo disponible para el backend y la interfaz arrancados en local por componentes.
- **CA-1.3:** Cuando se ejecute el comando documentado de parada, el sistema deberá detener RabbitMQ sin eliminar sus datos persistentes.
- **CA-1.4:** Donde se indiquen puertos distintos de los predeterminados mediante configuración externa al repositorio, el sistema deberá publicar RabbitMQ en esos puertos sin modificar ningún archivo versionado.
- **CA-1.5:** Donde se indiquen credenciales de RabbitMQ mediante configuración externa al repositorio, el sistema deberá usarlas, y ninguna credencial propia del proyecto deberá figurar en un archivo versionado.
- **CA-1.6:** El sistema deberá hacer accesible RabbitMQ únicamente desde la máquina de la persona usuaria.
- **CA-1.7:** Si no se indican credenciales mediante configuración externa, entonces el sistema deberá usar las credenciales predeterminadas de RabbitMQ, válidas solo para el desarrollo local gracias a CA-1.6, y la documentación deberá advertirlo.
- **CA-1.8:** El sistema deberá exponer la consola de administración web de RabbitMQ, accesible únicamente desde la máquina de la persona usuaria y con un puerto configurable mediante configuración externa al repositorio, para que se pueda contrastar lo que muestra el laboratorio con lo que ve RabbitMQ.

### RF-2 — Estado de la conexión con RabbitMQ
La interfaz debe informar de si la aplicación puede comunicarse con RabbitMQ y qué hacer si no puede.

Criterios de aceptación (EARS):
- **CA-2.1:** Cuando el usuario acceda al Laboratorio RabbitMQ, el sistema deberá consultar si el backend puede comunicarse con RabbitMQ.
- **CA-2.2:** Mientras la consulta esté en curso, el sistema deberá mostrar un indicador de carga.
- **CA-2.3:** Cuando RabbitMQ esté disponible, el sistema deberá mostrar el estado «Conectado» y habilitar las operaciones del laboratorio.
- **CA-2.4:** Si RabbitMQ no está disponible, o la consulta supera el tiempo máximo de espera (RNF-4), entonces el sistema deberá mostrar un mensaje en español indicando que RabbitMQ no está disponible y deshabilitar las operaciones del laboratorio.
- **CA-2.5:** Mientras se muestre el mensaje de RabbitMQ no disponible, el sistema deberá ofrecer una acción para reintentar la consulta sin recargar la página.
- **CA-2.6:** Cuando un reintento tenga éxito, el sistema deberá sustituir el mensaje de error por el estado «Conectado» y habilitar las operaciones.
- **CA-2.7:** Mientras un reintento esté en curso, el sistema deberá impedir que se lance otro.
- **CA-2.8:** Si el backend no responde, entonces el sistema deberá comportarse según el RF-3 de la especificación 01.

### RF-3 — Acceso al laboratorio desde el menú
El laboratorio debe ser accesible desde el menú de navegación.

Criterios de aceptación (EARS):
- **CA-3.1:** El sistema deberá mostrar la sección «Laboratorio RabbitMQ» del menú habilitada, y al activarla deberá navegar a ella.
- **CA-3.2:** El sistema deberá mantener visibles pero deshabilitadas las secciones «Laboratorio Kafka» y «Visualizador de flujo», con el comportamiento definido en CA-4.2 y CA-4.3 de la especificación 01.
- **CA-3.3:** Cuando el usuario esté en el Laboratorio RabbitMQ, el sistema deberá ofrecer acceso a estas áreas: exchanges, colas, bindings, publicación y consumidores.
- **CA-3.4:** Mientras el usuario esté en el laboratorio, el sistema deberá indicar en el menú que esa sección está activa.

### RF-4 — Gestión de exchanges
El usuario debe poder consultar, crear y eliminar exchanges de tipo direct, fanout y topic.

Criterios de aceptación (EARS):
- **CA-4.1:** Cuando el usuario acceda al área de exchanges, el sistema deberá listar los exchanges existentes en RabbitMQ con su nombre y su tipo, distinguiendo los predefinidos por RabbitMQ.
- **CA-4.2:** Cuando el usuario cree un exchange indicando un nombre y uno de los tipos direct, fanout o topic, el sistema deberá crearlo y mostrarlo en el listado.
- **CA-4.3:** El sistema deberá crear los exchanges de forma que se conserven al reiniciar RabbitMQ (RF-10).
- **CA-4.4:** Si el nombre está vacío, supera los 255 caracteres, contiene caracteres no permitidos por RabbitMQ o empieza por «amq.», entonces el sistema deberá rechazar la creación con un mensaje en español que explique la causa.
- **CA-4.5:** Si ya existe un exchange con ese nombre y el mismo tipo, entonces el sistema deberá informar de que ya existe sin duplicarlo; si existe con otro tipo, entonces deberá rechazar la creación explicando que el nombre ya está en uso con otro tipo, sin modificar el existente.
- **CA-4.6:** Cuando el usuario solicite eliminar un exchange, el sistema deberá pedir confirmación indicando que también se eliminarán los bindings del exchange, y solo tras confirmar deberá eliminarlo.
- **CA-4.7:** El sistema no deberá permitir eliminar ni modificar los exchanges predefinidos por RabbitMQ.

### RF-5 — Gestión de colas
El usuario debe poder consultar, crear, vaciar y eliminar colas, y asociar a una cola una dead-letter queue.

Criterios de aceptación (EARS):
- **CA-5.1:** Cuando el usuario acceda al área de colas, el sistema deberá listar las colas existentes con su nombre y, si la tiene, su dead-letter queue.
- **CA-5.2:** Cuando el usuario cree una cola indicando un nombre y, opcionalmente, una dead-letter queue elegida entre las colas existentes, el sistema deberá crearla y mostrarla en el listado.
- **CA-5.3:** El sistema deberá crear las colas de forma que se conserven al reiniciar RabbitMQ (RF-10).
- **CA-5.4:** Si el nombre está vacío, supera los 255 caracteres, contiene caracteres no permitidos por RabbitMQ o empieza por «amq.», entonces el sistema deberá rechazar la creación con un mensaje en español que explique la causa.
- **CA-5.5:** Si el usuario indica como dead-letter queue la propia cola que está creando, entonces el sistema deberá rechazar la creación explicando que una cola no puede ser su propia dead-letter queue.
- **CA-5.6:** Si ya existe una cola con ese nombre y la misma configuración, entonces el sistema deberá informar de que ya existe sin duplicarla; si existe con otra configuración, entonces deberá rechazar la creación explicando la diferencia, sin modificar la existente.
- **CA-5.7:** Cuando el usuario solicite eliminar una cola, el sistema deberá pedir confirmación indicando que se perderán sus mensajes y sus bindings, y solo tras confirmar deberá eliminarla.
- **CA-5.9:** Si la cola que se va a eliminar es la dead-letter queue de otras colas, entonces la confirmación deberá indicar cuáles son y advertir de que los mensajes que esas colas rechacen sin reencolar se descartarán; tras confirmar, el sistema deberá permitir la eliminación.
- **CA-5.8:** Cuando el usuario solicite vaciar una cola, el sistema deberá pedir confirmación y, tras confirmar, eliminar sus mensajes listos, manteniendo la cola y sus bindings. El vaciado no deberá afectar a los mensajes pendientes de confirmar por un consumidor.

### RF-6 — Gestión de bindings
El usuario debe poder consultar, crear y eliminar bindings entre un exchange y una cola.

Criterios de aceptación (EARS):
- **CA-6.1:** Cuando el usuario seleccione un exchange o una cola, el sistema deberá mostrar sus bindings con el exchange de origen, la cola de destino y la routing key o patrón.
- **CA-6.2:** Cuando el usuario cree un binding indicando un exchange, una cola y, según el tipo del exchange, una routing key o un patrón, el sistema deberá crearlo y mostrarlo.
- **CA-6.3:** Si el exchange es de tipo fanout, entonces el sistema no deberá solicitar routing key e indicará que el exchange difunde a todas sus colas.
- **CA-6.4:** Si el exchange es de tipo topic, entonces el sistema deberá aceptar patrones formados por palabras separadas por puntos, donde «*» sustituye exactamente una palabra y «#» sustituye cero o más palabras, y deberá explicar esta regla en el formulario.
- **CA-6.5:** Si la routing key o el patrón supera los 255 caracteres, entonces el sistema deberá rechazar el binding con un mensaje en español que explique la causa. Una routing key vacía será válida en los exchanges direct y topic.
- **CA-6.6:** El sistema deberá permitir que una misma cola tenga varios bindings, con distintas routing keys y con distintos exchanges.
- **CA-6.7:** Si ya existe un binding idéntico, entonces el sistema deberá informar de que ya existe sin duplicarlo.
- **CA-6.8:** Cuando el usuario elimine un binding, el sistema deberá eliminarlo sin pedir confirmación; los mensajes ya presentes en la cola no deberán verse afectados.
- **CA-6.9:** El sistema no deberá permitir crear ni eliminar bindings sobre el exchange por defecto de RabbitMQ.

### RF-7 — Publicación de mensajes
El usuario debe poder publicar mensajes en un exchange y saber qué ha ocurrido con ellos.

Criterios de aceptación (EARS):
- **CA-7.1:** Cuando el usuario indique un exchange, una routing key (si el tipo del exchange la usa) y un cuerpo de texto, y solicite publicar, el sistema deberá enviar el mensaje a RabbitMQ.
- **CA-7.2:** El sistema deberá publicar los mensajes de forma que se conserven al reiniciar RabbitMQ (RF-10).
- **CA-7.3:** Cuando RabbitMQ confirme la aceptación del mensaje, el sistema deberá mostrar que se ha publicado e indicar si el mensaje se ha enrutado al menos a una cola, sin detallar a cuáles.
- **CA-7.4:** Si ninguna cola recibe el mensaje porque ningún binding coincide, entonces el sistema deberá informar de forma explícita de que el mensaje no se ha podido enrutar y se ha descartado.
- **CA-7.5:** Si el cuerpo está vacío o supera los 64 KB, entonces el sistema deberá rechazar la publicación antes de enviarla, con un mensaje en español que explique la causa.
- **CA-7.6:** Si el exchange elegido ya no existe, entonces el sistema deberá informar del error sin perder el contenido que el usuario había escrito.
- **CA-7.7:** El sistema deberá permitir publicar en el exchange por defecto de RabbitMQ usando como routing key el nombre de una cola, para enviar un mensaje directamente a esa cola.
- **CA-7.8:** Mientras una publicación esté en curso, el sistema deberá impedir que se envíe otra duplicada.

### RF-8 — Estado de las colas e inspección de mensajes
El usuario debe poder ver el estado de las colas y el contenido de sus mensajes sin alterarlos.

Criterios de aceptación (EARS):
- **CA-8.1:** Cuando el usuario consulte las colas, el sistema deberá mostrar para cada una el número de mensajes listos, el de mensajes pendientes de confirmar y el total.
- **CA-8.2:** Cuando el usuario solicite actualizar el estado de las colas, el sistema deberá recargar los contadores sin recargar la página.
- **CA-8.3:** Cuando el usuario solicite inspeccionar una cola, el sistema deberá mostrar el contenido de sus primeros 50 mensajes listos, en el orden de la cola, sin retirarlos de ella y sin confirmarlos ni rechazarlos. Si la cola tiene más de 50 mensajes listos, entonces el sistema deberá indicar que solo se muestran los primeros 50 y cuántos hay en total.
- **CA-8.4:** El sistema deberá mostrar de cada mensaje inspeccionado su cuerpo, su routing key, el exchange por el que llegó y si ha sido reentregado. Como RabbitMQ no ofrece una lectura sin efecto, la inspección puede marcar los mensajes como reentregados, y el sistema deberá advertirlo al usuario junto al resultado de la inspección.
- **CA-8.5:** Cuando el usuario inspeccione una dead-letter queue, el sistema deberá mostrar además la cola de origen y el motivo por el que el mensaje llegó hasta allí, siempre que RabbitMQ los proporcione.
- **CA-8.6:** Si la cola no tiene mensajes listos, entonces el sistema deberá indicarlo.
- **CA-8.7:** La inspección no deberá mostrar los mensajes pendientes de confirmar por un consumidor; el contador de pendientes indicará cuántos hay.
- **CA-8.8:** Cuando el usuario realice una operación que modifique el contenido de una cola (publicar, vaciar, eliminar, o confirmar o rechazar un mensaje), el sistema deberá actualizar los contadores mostrados.
- **CA-8.9:** Mientras el área de colas esté abierta, el sistema deberá actualizar los contadores automáticamente cada 2 segundos, sin mostrar un indicador de carga ni borrar el listado si una actualización falla. Esto permite ver en tiempo real los mensajes que publican los emisores del generador (especificación 04) y consumen los consumidores. La acción de CA-8.2 sigue disponible.
- **CA-8.10:** Mientras el panel de inspección de una cola esté abierto, el sistema deberá volver a leer sus mensajes cada 2 segundos, salvo que el usuario desactive esa opción. Como cada lectura puede marcar los mensajes como reentregados (CA-8.4), el usuario puede desactivar la lectura automática desde el propio panel.

### RF-9 — Consumidor interactivo
El usuario debe poder consumir mensajes de una cola decidiendo qué hacer con cada uno.

Criterios de aceptación (EARS):
- **CA-9.1:** Cuando el usuario inicie un consumidor sobre una cola eligiendo el modo de confirmación automático o manual, el sistema deberá empezar a recibir los mensajes de esa cola.
- **CA-9.2:** Mientras un consumidor esté activo, el sistema deberá mostrar cada mensaje recibido, con su cuerpo, routing key, exchange de origen e indicación de reentrega, a medida que llega.
- **CA-9.3:** Si el consumidor está en modo automático, entonces el sistema deberá confirmar cada mensaje al recibirlo, de modo que desaparezca de la cola.
- **CA-9.4:** Si el consumidor está en modo manual, entonces el sistema deberá mantener cada mensaje como pendiente de confirmar hasta que el usuario decida, ofreciendo tres acciones: confirmar, rechazar y reencolar, y rechazar sin reencolar.
- **CA-9.5:** Cuando el usuario confirme un mensaje, el sistema deberá eliminarlo de la cola.
- **CA-9.6:** Cuando el usuario rechace un mensaje y lo reencole, el sistema deberá devolverlo a la cola, y cuando se reciba de nuevo deberá indicar que ha sido reentregado.
- **CA-9.7:** Cuando el usuario rechace un mensaje sin reencolarlo, el sistema deberá enviarlo a la dead-letter queue de esa cola si la tiene; si no la tiene, entonces deberá descartarlo e informar al usuario de que el mensaje se ha descartado.
- **CA-9.8:** Cuando el usuario detenga un consumidor, el sistema deberá devolver a la cola los mensajes que tuviera pendientes de confirmar.
- **CA-9.9:** Si la cola se elimina o se pierde la comunicación con RabbitMQ mientras hay un consumidor activo, entonces el sistema deberá finalizar el consumidor y avisar al usuario con un mensaje en español.
- **CA-9.10:** Mientras una acción sobre un mensaje esté en curso, el sistema deberá impedir que se aplique otra distinta sobre el mismo mensaje, y una vez resuelto deberá impedir que se vuelva a resolver.
- **CA-9.11:** El sistema deberá permitir iniciar varios consumidores simultáneos sobre la misma cola, identificables cada uno por un nombre, de modo que el usuario pueda observar cómo RabbitMQ reparte los mensajes entre ellos.
- **CA-9.12:** Si el consumidor está en modo manual, entonces el sistema deberá mantener como máximo un mensaje pendiente de confirmar por consumidor: no recibirá el siguiente hasta que el usuario resuelva el actual.
- **CA-9.13:** Cuando el usuario cierre o recargue la página, el sistema deberá detener los consumidores iniciados desde ella y devolver a la cola sus mensajes pendientes de confirmar.
- **CA-9.14:** Cuando el usuario intente salir del Laboratorio RabbitMQ habiendo consumidores activos, el sistema deberá pedir confirmación indicando que se detendrán; si el usuario confirma, deberá detenerlos y devolver a la cola sus mensajes pendientes de confirmar, y si cancela, deberá permanecer en el laboratorio. Cambiar de área dentro del laboratorio no deberá detener los consumidores.

### RF-10 — Persistencia y reinicio
La topología y los mensajes deben conservarse entre reinicios de RabbitMQ, y el usuario debe poder empezar de cero.

Criterios de aceptación (EARS):
- **CA-10.1:** Cuando se detenga y se vuelva a levantar RabbitMQ sin eliminar sus datos, el sistema deberá conservar los exchanges, colas y bindings creados.
- **CA-10.2:** Cuando se detenga y se vuelva a levantar RabbitMQ sin eliminar sus datos, el sistema deberá conservar los mensajes listos de las colas, incluidas las dead-letter queues.
- **CA-10.3:** Si había mensajes pendientes de confirmar cuando RabbitMQ se detuvo, entonces el sistema deberá devolverlos a la cola al volver a levantarlo.
- **CA-10.4:** El sistema no deberá conservar los consumidores activos tras un reinicio; el usuario deberá iniciarlos de nuevo.
- **CA-10.5:** El sistema deberá documentar el comando para eliminar todos los datos persistentes de RabbitMQ y empezar con un laboratorio vacío.
- **CA-10.6:** Cuando se eliminen los datos persistentes y se vuelva a levantar RabbitMQ, el sistema deberá mostrar el laboratorio sin exchanges, colas ni mensajes creados por el usuario.
- **CA-10.7:** El sistema no deberá introducir ningún otro almacenamiento de datos: el estado del laboratorio deberá residir únicamente en RabbitMQ.

### RF-11 — Gestión de errores y disponibilidad
La interfaz debe gestionar de forma comprensible los fallos de RabbitMQ y de las operaciones.

Criterios de aceptación (EARS):
- **CA-11.1:** Si RabbitMQ deja de estar disponible mientras la interfaz está abierta, entonces la siguiente operación deberá mostrar un mensaje en español indicando que RabbitMQ no está disponible y ofrecer reintentar.
- **CA-11.2:** Cuando RabbitMQ vuelva a estar disponible, el sistema deberá restablecer la comunicación sin reiniciar el backend y sin recargar la página.
- **CA-11.3:** Si RabbitMQ rechaza una operación (por ejemplo, porque un elemento ya no existe o hay un conflicto), entonces el sistema deberá mostrar un mensaje en español con la causa comprensible, sin trazas, detalles técnicos internos ni credenciales.
- **CA-11.4:** Si una operación supera el tiempo máximo de espera (RNF-4), entonces el sistema deberá mostrar un mensaje en español indicando que se agotó el tiempo, en lugar de quedarse cargando indefinidamente.
- **CA-11.5:** Mientras una operación esté en curso, el sistema deberá impedir que se repita desde el mismo control.
- **CA-11.6:** Si una operación falla, entonces el sistema no deberá invalidar ni borrar el resto de los datos mostrados.

### RF-12 — Contrato de la API y pruebas automatizadas
La funcionalidad debe quedar documentada en el contrato de la API y cubierta por pruebas automatizadas.

Criterios de aceptación (EARS):
- **CA-12.1:** El sistema deberá documentar en el contrato de la API todas las operaciones nuevas, de forma coherente con la implementación.
- **CA-12.2:** Cuando se ejecuten las pruebas del backend, el sistema deberá verificar las reglas del laboratorio (validación de nombres, patrones y cuerpos, y reglas de las dead-letter queues) sin necesidad de que RabbitMQ esté en marcha.
- **CA-12.3:** Cuando se ejecuten las pruebas de integración contra una instancia real de RabbitMQ, el sistema deberá verificar:
  - el enrutado de los exchanges direct, fanout y topic, incluidos los patrones «*» y «#»;
  - el mensaje no enrutable;
  - la confirmación y el rechazo con y sin reencolado;
  - el rechazo sin reencolado con y sin dead-letter queue;
  - la conservación de la topología y los mensajes tras reiniciar RabbitMQ.
- **CA-12.4:** Cuando se ejecuten las pruebas de la interfaz, el sistema deberá verificar los estados de carga, éxito y error, los formularios con sus validaciones, las confirmaciones de borrado, la publicación, la inspección y el consumidor interactivo.
- **CA-12.5:** Las pruebas de la interfaz deberán poder ejecutarse sin que el backend ni RabbitMQ estén en marcha.
- **CA-12.6:** El sistema deberá mantener las pruebas de la especificación 01 pasando, actualizando únicamente las que verifican las secciones deshabilitadas del menú.

### RF-13 — Documentación
El repositorio debe documentar en español cómo usar e instalar la integración con RabbitMQ.

Criterios de aceptación (EARS):
- **CA-13.1:** El sistema deberá documentar los requisitos previos y los pasos de arranque con RabbitMQ, tanto el arranque completo como el arranque de RabbitMQ por separado para el desarrollo local.
- **CA-13.2:** El sistema deberá documentar los puertos predeterminados de RabbitMQ y de su consola de administración y cómo cambiarlos, así como la configuración de las credenciales y la advertencia de CA-1.7, sin modificar archivos versionados.
- **CA-13.3:** El sistema deberá documentar cómo detener la infraestructura conservando los datos y cómo eliminarlos para empezar de cero.
- **CA-13.4:** El sistema deberá documentar cómo ejecutar las pruebas de integración con RabbitMQ y qué necesitan para funcionar.
- **CA-13.5:** El sistema deberá documentar un escenario guiado de ejemplo para comprobar el funcionamiento: crear un exchange, una cola con dead-letter queue y un binding, publicar un mensaje, consumirlo y rechazarlo.
- **CA-13.6:** El sistema deberá documentar qué hacer cuando RabbitMQ no arranca o un puerto está ocupado.
- **CA-13.7:** El sistema deberá documentar el riesgo de los ciclos entre varias dead-letter queues, en los que RabbitMQ descarta los mensajes en silencio (CL-11).

## 5. Requisitos no funcionales

- **RNF-1 (Idioma):** los textos visibles para el usuario y la documentación estarán en español; los identificadores de código, en inglés.
- **RNF-2 (Reproducibilidad):** una persona con los requisitos previos documentados deberá poder arrancar la aplicación con RabbitMQ siguiendo únicamente la documentación, sin pasos no descritos.
- **RNF-3 (Tiempo de respuesta):** en un entorno local con todo en marcha, las operaciones de consulta, creación, eliminación y publicación deberán responder en menos de 2 segundos, y un mensaje disponible deberá aparecer en un consumidor activo en menos de 2 segundos.
- **RNF-4 (Tiempo de espera):** la interfaz deberá dejar de esperar la respuesta de cualquier operación tras 20 segundos, igual que en la especificación 01, para mostrar el error en lugar de quedarse cargando indefinidamente.
- **RNF-5 (Seguridad):** el repositorio no contendrá credenciales, secretos ni configuración local sensible. Los cuerpos de los mensajes y los nombres introducidos por el usuario se mostrarán siempre como texto, sin interpretarse como contenido ejecutable. Los mensajes de error no revelarán credenciales ni detalles internos.
- **RNF-6 (Mínimas dependencias):** no se añadirán dependencias que no sean necesarias, y cada una requerirá justificación y aprobación, conforme a la constitución.
- **RNF-7 (Separación de responsabilidades):** las reglas del laboratorio (validaciones, reglas de las dead-letter queues y significado de las acciones del usuario) deberán ser independientes de los detalles de RabbitMQ y de la interfaz, y poder verificarse sin RabbitMQ en marcha.
- **RNF-8 (Accesibilidad básica):** los estados y errores deberán ser perceptibles por tecnologías de asistencia, los formularios deberán tener sus campos etiquetados, y todas las acciones —incluidas confirmar y rechazar mensajes— deberán poder activarse con el teclado.
- **RNF-9 (Compatibilidad):** la interfaz se verificará en la última versión estable de Google Chrome de escritorio, igual que en la especificación 01.
- **RNF-10 (Persistencia explícita):** el único almacenamiento de datos del laboratorio será el propio de RabbitMQ; no se añadirá ninguna otra tecnología de almacenamiento ni esquema sin una especificación previa.

## 6. Casos límite

- **CL-1:** RabbitMQ está detenido al abrir el laboratorio → se muestra que no está disponible, las operaciones se deshabilitan y se ofrece reintentar (RF-2).
- **CL-2:** RabbitMQ arranca más tarde que el backend, algo habitual en el arranque completo → el backend arranca igualmente y, al estar RabbitMQ listo, un reintento muestra «Conectado» sin reiniciar nada (CA-11.2).
- **CL-3:** RabbitMQ se detiene con un consumidor activo → el consumidor finaliza con un aviso, y los mensajes que tenía pendientes de confirmar vuelven a la cola al recuperarse RabbitMQ (CA-9.9, CA-10.3).
- **CL-4:** Se publica un mensaje y no hay ningún binding que coincida → se informa de que no se pudo enrutar y se descartó, en lugar de parecer un éxito (CA-7.4).
- **CL-5:** Se rechaza un mensaje sin reencolar en una cola sin dead-letter queue → el mensaje se descarta y se informa al usuario (CA-9.7).
- **CL-6:** Se rechaza y reencola un mensaje repetidamente → vuelve a la cola cada vez, marcado como reentregado, sin límite. Es un comportamiento esperado de RabbitMQ y útil para aprender el efecto de un reencolado sin fin.
- **CL-7:** Se elimina un exchange con bindings → los bindings desaparecen con él; los mensajes ya en las colas no se pierden (CA-4.6).
- **CL-8:** Se elimina una cola con mensajes → se pide confirmación porque se pierden sus mensajes y bindings (CA-5.7). Si además es la dead-letter queue de otras colas, la confirmación las indica y advierte de que sus mensajes rechazados se descartarán (CA-5.9).
- **CL-9:** Se crea un elemento con un nombre ya existente → si es idéntico, se informa de que ya existe; si difiere, se rechaza sin tocar el existente (CA-4.5, CA-5.6).
- **CL-10:** Un nombre vacío, demasiado largo, con caracteres no permitidos o con el prefijo reservado «amq.» → se rechaza con un mensaje que explica la causa (CA-4.4, CA-5.4).
- **CL-11:** Una cola se configura como su propia dead-letter queue → se rechaza (CA-5.5). Los ciclos entre varias colas no se detectan en esta especificación; RabbitMQ descarta en silencio los mensajes que entran en un ciclo, y el riesgo se documenta (CA-13.7).
- **CL-12:** Un exchange, una cola o un binding se elimina desde otra herramienta mientras la interfaz sigue abierta → la interfaz muestra el estado de su última actualización; la siguiente operación sobre el elemento inexistente falla con un mensaje claro (CA-11.3).
- **CL-13:** El usuario pulsa varias veces seguidas crear, publicar, reintentar, confirmar o rechazar → solo se ejecuta una operación (CA-2.7, CA-7.8, CA-9.10, CA-11.5).
- **CL-14:** Se vacía una cola que tiene mensajes pendientes de confirmar por un consumidor → el vaciado elimina solo los mensajes listos (CA-5.8).
- **CL-15:** Se elimina una cola mientras tiene un consumidor activo → el consumidor finaliza con un aviso (CA-9.9).
- **CL-16:** Un puerto de RabbitMQ ya está ocupado en la máquina → el arranque falla con el error propio del componente; la persona puede elegir otro puerto mediante configuración externa al repositorio (CA-1.4, CA-13.6).
- **CL-17:** El cuerpo de un mensaje contiene caracteres especiales o marcado (por ejemplo, etiquetas HTML) → se muestra como texto literal (RNF-5).
- **CL-18:** Se publica en el exchange por defecto con el nombre de una cola que no existe → el mensaje no se puede enrutar y se informa como en CL-4 (CA-7.4, CA-7.7).
- **CL-19:** Se detienen y se vuelven a levantar los contenedores sin eliminar los datos → la topología y los mensajes siguen ahí; con los datos eliminados, el laboratorio está vacío (RF-10).
- **CL-20:** El usuario cierra o recarga la página con consumidores activos → los consumidores se detienen y sus mensajes pendientes de confirmar vuelven a la cola, marcados como reentregados cuando se reciban de nuevo (CA-9.13).
- **CL-21:** Hay dos consumidores sobre la misma cola → RabbitMQ reparte los mensajes entre ambos, y cada consumidor manual retiene como máximo uno a la vez (CA-9.11, CA-9.12).
- **CL-22:** Una cola tiene más de 50 mensajes listos → la inspección muestra solo los 50 primeros e indica el total (CA-8.3).
- **CL-23:** Se abren dos pestañas con consumidores activos → cada pestaña gestiona sus propios consumidores, y al cerrar una solo se detienen los suyos (CA-9.13).
- **CL-24:** El usuario cambia de área dentro del laboratorio con consumidores activos → siguen activos y sus mensajes se conservan en pantalla; si sale del laboratorio, se le pide confirmación (CA-9.14).
- **CL-25:** El usuario inspecciona una cola y después la consume → los mensajes pueden aparecer como reentregados aunque ningún consumidor los hubiera recibido antes; la inspección lo advierte (CA-8.4).

## 7. Fuera de alcance

- Apache Kafka y su laboratorio (especificación posterior).
- El visualizador de flujo y cualquier representación gráfica animada del recorrido de los mensajes.
- Exchanges de tipo headers y otros mecanismos de enrutado avanzados (alternate exchange, plugins de RabbitMQ).
- Tipos de cola distintos de la cola estándar durable (quorum, stream, exclusiva, de autoborrado, con prioridad).
- Caducidad de mensajes y de colas (TTL) y límites de longitud, y por tanto el envío a dead-letter queue por estas causas: en esta especificación solo se envía por rechazo sin reencolado.
- Transacciones de publicación y confirmaciones de publicador avanzadas.
- Indicar a qué colas concretas se ha enrutado cada mensaje publicado: corresponde al visualizador de flujo.
- Detección de ciclos entre varias dead-letter queues.
- Consumidor automático con comportamiento configurable (por ejemplo, fallos simulados) y configuración avanzada del consumidor más allá de lo definido en RF-9.
- Varios virtual hosts, y la gestión de usuarios y permisos de RabbitMQ.
- Clústeres, federación, alta disponibilidad y balanceo.
- Autenticación y autorización en la aplicación.
- Almacenamiento de datos propio de la aplicación, como un historial de operaciones y mensajes.
- Una acción única de «restablecer todo el laboratorio» desde la interfaz (se hace elemento a elemento o eliminando los datos persistentes, según CA-10.5).
- Importar, exportar o compartir topologías, y escenarios predefinidos cargables desde la interfaz.
- Monitorización avanzada: métricas, trazas y paneles.
- Integración continua y despliegue en entornos distintos del local.
- Verificación en navegadores distintos de Google Chrome o en dispositivos móviles.

## 8. Criterios de finalización

La funcionalidad se considera terminada cuando:

1. Se cumplen todos los criterios de aceptación de RF-1 a RF-13.
2. La aplicación arranca en las dos modalidades (completa con un único comando, y local por componentes con RabbitMQ levantado por separado) y, en ambas, el laboratorio muestra «Conectado».
3. **Enrutado:** con un exchange direct, uno fanout y uno topic, cada uno con sus colas y bindings, un mensaje publicado llega exactamente a las colas esperadas según el tipo y los patrones «*» y «#», y se informa cuando no se puede enrutar.
4. **Confirmaciones y dead-letter queues:** con un consumidor manual, confirmar elimina el mensaje; rechazar y reencolar lo devuelve marcado como reentregado; rechazar sin reencolar lo envía a la dead-letter queue, donde puede inspeccionarse con su cola de origen y su motivo; y en una cola sin dead-letter queue el mensaje se descarta y se informa. Con dos consumidores sobre la misma cola, los mensajes se reparten entre ellos.
5. **Persistencia:** tras detener y volver a levantar RabbitMQ sin eliminar sus datos, siguen la topología y los mensajes; tras eliminar los datos con el comando documentado, el laboratorio está vacío.
6. **Errores:** con RabbitMQ detenido se muestra el error con la opción de reintentar y, al levantarlo, el reintento muestra «Conectado».
7. Las pruebas automatizadas del backend (incluidas las de integración con RabbitMQ real) y de la interfaz se han ejecutado y pasan, y su resultado ha sido comprobado. Las pruebas de la especificación 01 siguen pasando.
8. El contrato de la API refleja las operaciones nuevas y es coherente con la implementación.
9. La documentación se ha seguido desde un clon limpio del repositorio, sin necesidad de pasos adicionales, incluido el escenario guiado de ejemplo.
10. No hay errores conocidos sin documentar.

## 9. Dudas abiertas

No quedan dudas abiertas. Todas las aclaraciones se han incorporado a la especificación: credenciales y consola de administración (CA-1.5, CA-1.7, CA-1.8, CA-13.2), colas destinatarias (CA-7.3), tamaño del mensaje (CA-7.5), contadores (CA-8.2, CA-8.8, CA-8.9, CA-8.10), inspección (CA-8.3), consumidores (CA-9.11 a CA-9.13), dead-letter queue en uso (CA-5.9) y ciclos de dead-letter queues (CL-11, CA-13.7).

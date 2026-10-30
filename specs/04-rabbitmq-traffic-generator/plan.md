# Plan 04 — Generador de tráfico del Laboratorio RabbitMQ

- **Estado:** Aprobado
- **Fecha:** 2026-10-30
- **Especificación:** [spec.md](spec.md)

Backend con un paquete nuevo y frontend con una pestaña nueva. Sin dependencias nuevas ni infraestructura nueva.

## Pensado para reutilizarlo con Kafka

El laboratorio de Kafka necesitará emisores parecidos. Para no hacer el trabajo dos veces, se separa lo que es común de lo que es de RabbitMQ, sin diseñar nada específico de Kafka:

- **Común, sin nada de RabbitMQ:** plantillas del cuerpo, planificador de emisores, límite global y máximos, contadores y ritmo real. Hablan con el exterior solo mediante una interfaz pequeña («publicar este cuerpo en este destino») cuyo primer y único adaptador es el de RabbitMQ.
- **Específico de RabbitMQ:** el destino (exchange con routing key, o cola), el adaptador de publicación y los consumidores simulados, que dependen de confirmar y rechazar mensajes y no se parecen a los grupos de consumidores de Kafka.
- **En el frontend:** la lista de emisores y el formulario de frecuencia, plantillas y vista previa serán componentes compartidos con un hueco para los campos del destino. Solo el formulario de destino de RabbitMQ es específico.
- **Qué no se hace ahora:** un «destino universal». Se generalizará cuando existan los dos casos reales.

## Backend

Paquetes `com.messaginglab.generator` (núcleo, sin dependencias de RabbitMQ; no importa `com.rabbitmq` ni el modelo de RabbitMQ) y `com.messaginglab.generator.rabbitmq` (destino, adaptador y consumidores simulados). Se comprueba igual que las capas de la especificación 02.

- **Plantillas (núcleo):** un texto con marcadores como `{{numero(20,30)}}`, `{{deriva(22,18,26,0.3)}}`, `{{senoidal(0,100,10)}}`, `{{contador}}`, `{{ahora}}`, `{{elegir(a,b,c)}}`, `{{booleano}}` y `{{ejes(6,-3.14,3.14)}}`. Un analizador con una lista cerrada de funciones, sin evaluar código. El generador de números es inyectable para pruebas deterministas. Las plantillas industriales son constantes del código.
- **Emisores (núcleo):** un planificador con un hilo por emisor activo (máximo 20) que llama a la interfaz de publicación. Cuenta publicados, no enrutados y ritmo real. El adaptador de RabbitMQ publica con el puerto `BrokerPublisher` y no con el `PublishService`, porque este consulta la API de gestión para comprobar el exchange en cada mensaje, algo inasumible a cien mensajes por segundo: el destino se comprueba al poner el emisor en marcha y, si desaparece después, lo notifica RabbitMQ al publicar. El destino puede ser un exchange con routing key o una cola mediante el exchange por defecto.
- **Límite (núcleo):** un servicio de ajustes con el límite global (100 por defecto, tope 500). Comprueba la suma de frecuencias al iniciar, reanudar o bajar el límite.
- **Consumidores simulados:** usan el puerto `BrokerConsumers` existente con una sesión interna del sistema, no ligada a ningún navegador. Cada mensaje espera el tiempo configurado y se resuelve con confirmar o con la acción de fallo elegida.
- **Seguimiento de colas:** el aviso de más de 10 000 mensajes sale del listado de colas que ya existe.
- **Contrato** (`api.yml`), etiqueta `RabbitMqGenerator`, bajo `/api/rabbitmq/generator`: emisores (listar, crear, editar, eliminar y `acción` iniciar/pausar/reanudar/parar), vista previa de una plantilla, plantillas predefinidas, consumidores simulados (listar, crear, eliminar, iniciar/parar), ajustes (leer y cambiar el límite) y `parar todo`. Los errores usan el `ApiError` de siempre.
- **Cierre:** al apagar el backend se detiene todo (`@PreDestroy`).
- **Frecuencia sin deriva:** el siguiente envío se calcula sobre el ritmo previsto y no desde que termina el anterior. Sin esto, el tiempo de publicar se sumaba al intervalo y a 100 por segundo se llegaba a unos 78. Si el publicador no da abasto, envía seguido sin acumular retraso.
- **Rendimiento medido (T05):** el publicador actual abre un canal con confirmación por cada mensaje. En las pruebas de integración, un emisor a 100 por segundo mantiene 100, y uno a 500 por segundo (el máximo) mantiene 500 en esta máquina. No hace falta reutilizar canales; en un entorno más lento el ritmo real podría quedarse por debajo del pedido.

## Frontend (`features/rabbitmq/generator/`)

- Componentes compartidos en `shared/generator/` (lista de emisores, formulario de frecuencia, plantillas y vista previa, con un hueco para el destino) y la pestaña «Generador» tras «Consumidores» en `features/rabbitmq/generator/`, con el formulario de destino de RabbitMQ y los consumidores simulados. Un servicio con las llamadas y un refresco cada 2 segundos mientras la pantalla está abierta.
- Secciones: límite global y «Parar todo», emisores (lista con controles y formulario con plantillas y vista previa) y consumidores simulados (lista y formulario).
- Mismos patrones que las demás áreas: formularios reactivos con las validaciones del servidor, `ApiError`, `RabbitMqConnection.reportFailure` y el diálogo de confirmación.

## Pruebas

- **Sin Docker:** una comprobación de que el núcleo no depende de RabbitMQ, el analizador de plantillas (valores, rangos, errores, 64 KB), los servicios con un publicador y un reloj falsos (frecuencia, límites, parada automática, destino eliminado), los controladores y, en el frontend, servicios y pantallas.
- **Con Docker (`*IT`):** un emisor publica a su ritmo contra RabbitMQ real y se detiene; se respeta el límite; un emisor rápido llena la cola de un consumidor lento; un consumidor simulado con fallos manda mensajes a la dead-letter queue.

## Riesgos

- **Que la frecuencia real se desvíe** con muchos emisores: se mide en las `*IT` y se documenta el límite.
- **Llenar RabbitMQ** si se deja un emisor rápido mucho tiempo: lo cubren el límite global, los máximos, el aviso de cola y «Parar todo».
- **Formatos de mensaje distintos a los reales:** las plantillas son genéricas y editables. Más adelante se pueden añadir otras parecidas a las vuestras.

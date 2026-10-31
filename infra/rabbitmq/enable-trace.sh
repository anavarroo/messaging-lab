#!/usr/bin/env bash
# Arranca RabbitMQ y mantiene activado el trazado (firehose) del virtual host "/".
#
# "rabbitmqctl trace_on" no se puede fijar por configuración y se pierde cada vez que el nodo se reinicia, así
# que se repite en segundo plano (es idempotente). El visualizador de flujo del laboratorio se alimenta de él.
# El trazado copia cada mensaje dentro de RabbitMQ: es útil en un laboratorio, no en producción.
set -u

(
  while true; do
    HOME=/var/lib/rabbitmq gosu rabbitmq rabbitmqctl trace_on >/dev/null 2>&1
    sleep 15
  done
) &

exec docker-entrypoint.sh rabbitmq-server

/** Sección del menú de navegación. Las secciones deshabilitadas todavía no tienen ruta. */
export interface NavSection {
  label: string;
  enabled: boolean;
  path?: string;
  description?: string;
}

export const NAV_SECTIONS: readonly NavSection[] = [
  { label: 'Estado', enabled: true, path: '/status' },
  {
    label: 'Laboratorio RabbitMQ',
    enabled: false,
    description:
      'Colas, exchanges, bindings, routing keys, publicación, acknowledgements y dead-letter queues.',
  },
  {
    label: 'Laboratorio Kafka',
    enabled: false,
    description: 'Topics, particiones, offsets, productores, consumer groups y lag.',
  },
  {
    label: 'Visualizador de flujo',
    enabled: false,
    description:
      'Publicar un mensaje y seguir visualmente su recorrido, las entregas y los errores.',
  },
];

import { Routes } from '@angular/router';
import { BindingsPage } from './features/rabbitmq/bindings/bindings-page';
import { RabbitMqConnection } from './features/rabbitmq/connection/rabbitmq-connection';
import { ConsumerSessionService } from './features/rabbitmq/consumers/consumer-session.service';
import { ConsumersPage } from './features/rabbitmq/consumers/consumers-page/consumers-page';
import { ExchangesPage } from './features/rabbitmq/exchanges/exchanges-page';
import { PublishPage } from './features/rabbitmq/publish/publish-page';
import { QueuesPage } from './features/rabbitmq/queues/queues-page';
import { AreaScrollMemory } from './features/rabbitmq/rabbitmq-lab/area-scroll-memory';
import { RabbitMqLab } from './features/rabbitmq/rabbitmq-lab/rabbitmq-lab';
import { StatusPage } from './features/status/status-page/status-page';

export const routes: Routes = [
  { path: '', redirectTo: 'status', pathMatch: 'full' },
  { path: 'status', component: StatusPage },
  {
    path: 'rabbitmq',
    component: RabbitMqLab,
    // la conexión y la sesión de consumidores viven mientras se está en el laboratorio
    providers: [RabbitMqConnection, ConsumerSessionService, AreaScrollMemory],
    // salir del laboratorio con consumidores activos pide confirmación; entre áreas no se pregunta (CA-9.14)
    canDeactivate: [(lab: RabbitMqLab) => lab.confirmLeave()],
    children: [
      { path: '', redirectTo: 'exchanges', pathMatch: 'full' },
      // la guía es contenido extenso y estático: se descarga al abrirla
      { path: 'guide', loadComponent: () => import('./features/rabbitmq/guide/guide-page').then((m) => m.GuidePage) },
      { path: 'exchanges', component: ExchangesPage },
      { path: 'queues', component: QueuesPage },
      { path: 'bindings', component: BindingsPage },
      { path: 'publish', component: PublishPage },
      { path: 'consumers', component: ConsumersPage },
      // el generador es la pantalla más pesada y la menos usada: se descarga al abrirla
      {
        path: 'flow',
        loadComponent: () => import('./features/rabbitmq/flow/rabbitmq-flow-page').then((m) => m.RabbitMqFlowPage),
      },
      {
        path: 'generator',
        loadComponent: () => import('./features/rabbitmq/generator/generator-page').then((m) => m.GeneratorPage),
      },
    ],
  },
  { path: '**', redirectTo: 'status' },
];

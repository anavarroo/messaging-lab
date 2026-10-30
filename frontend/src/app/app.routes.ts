import { Routes } from '@angular/router';
import { StatusPage } from './features/status/status-page/status-page';

export const routes: Routes = [
  { path: '', redirectTo: 'status', pathMatch: 'full' },
  { path: 'status', component: StatusPage },
  { path: '**', redirectTo: 'status' },
];

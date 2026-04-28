import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'app', pathMatch: 'full' },
  {
    path: 'app',
    loadComponent: () =>
      import('./pages/app-page.component').then((m) => m.AppPageComponent),
  },
  {
    path: 'sidecar',
    loadComponent: () =>
      import('./pages/sidecar-page.component').then((m) => m.SidecarPageComponent),
  },
  {
    path: 'agents',
    loadComponent: () =>
      import('./pages/agents-page.component').then((m) => m.AgentsPageComponent),
  },
  {
    path: 'measurements',
    loadComponent: () =>
      import('./pages/measurements-page.component').then(
        (m) => m.MeasurementsPageComponent,
      ),
  },
];

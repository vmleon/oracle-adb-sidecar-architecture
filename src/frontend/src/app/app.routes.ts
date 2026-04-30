import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'risk', pathMatch: 'full' },
  {
    path: 'risk',
    loadComponent: () =>
      import('./pages/risk-page.component').then((m) => m.RiskPageComponent),
  },
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

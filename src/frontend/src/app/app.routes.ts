import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'risk', pathMatch: 'full' },
  {
    path: 'risk',
    loadComponent: () =>
      import('./pages/risk-page.component').then((m) => m.RiskPageComponent),
  },
  {
    path: 'fraud',
    loadComponent: () =>
      import('./pages/fraud-page.component').then((m) => m.FraudPageComponent),
  },
  {
    path: 'app',
    loadComponent: () =>
      import('./pages/app-page.component').then((m) => m.AppPageComponent),
  },
  {
    path: 'ai-data-gateway',
    loadComponent: () =>
      import('./pages/ai-data-gateway-page.component').then(
        (m) => m.AiDataGatewayPageComponent,
      ),
  },
  { path: 'sidecar', redirectTo: 'ai-data-gateway', pathMatch: 'full' },
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

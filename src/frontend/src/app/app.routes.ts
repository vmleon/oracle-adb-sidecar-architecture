import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'demo', pathMatch: 'full' },
  {
    path: 'demo',
    loadComponent: () =>
      import('./versions/versions.component').then((m) => m.VersionsComponent),
  },
];

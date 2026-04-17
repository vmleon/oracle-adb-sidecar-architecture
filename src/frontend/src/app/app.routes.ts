import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'versions', pathMatch: 'full' },
  {
    path: 'versions',
    loadComponent: () =>
      import('./versions/versions.component').then((m) => m.VersionsComponent),
  },
];

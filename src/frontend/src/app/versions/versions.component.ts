import { Component, inject, signal } from '@angular/core';
import { VersionsService, VersionsResponse } from '../versions.service';

type DbKey = keyof VersionsResponse;

@Component({
  selector: 'app-versions',
  template: `
    <h2>Database versions</h2>
    <p class="subtitle">
      Fetches the version of the ADB 26ai AI sidecar plus the three production databases it federates with.
      A smoke test that every datasource in the architecture is reachable.
    </p>

    <button (click)="load()" [disabled]="loading()">
      {{ loading() ? 'Querying...' : 'Get versions' }}
    </button>

    @if (error()) {
      <p class="error">{{ error() }}</p>
    }

    @if (versions(); as v) {
      <div class="grid">
        @for (db of dbs; track db.key) {
          <article class="card">
            <h3>{{ db.label }}</h3>
            <pre>{{ v[db.key] }}</pre>
          </article>
        }
      </div>
    }
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; }
    .subtitle { color: #9B9590; margin-bottom: 1.25rem; font-size: 0.9rem; }
    .grid {
      margin-top: 1.5rem;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: 1rem;
    }
    .card {
      background: #2C2723;
      border: 1px solid #3C3835;
      border-radius: 8px;
      padding: 1rem;
    }
    .card h3 {
      margin: 0 0 0.5rem;
      color: #C74634;
      font-size: 0.95rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .error { color: #C74634; margin-top: 1rem; }
  `,
})
export class VersionsComponent {
  private service = inject(VersionsService);

  loading = signal(false);
  error = signal('');
  versions = signal<VersionsResponse | null>(null);

  dbs: { key: DbKey; label: string }[] = [
    { key: 'adb', label: 'ADB 26ai — AI sidecar' },
    { key: 'oracle', label: 'Oracle Free 26ai — production (simulated)' },
    { key: 'postgres', label: 'PostgreSQL 18 — production (simulated)' },
    { key: 'mongo', label: 'MongoDB 8 — production (simulated)' },
  ];

  load() {
    this.loading.set(true);
    this.error.set('');
    this.versions.set(null);
    this.service.fetch().subscribe({
      next: (res) => {
        this.versions.set(res);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message || 'Request failed');
        this.loading.set(false);
      },
    });
  }
}

import { Component, signal } from '@angular/core';
import { CardComponent, CardState } from '../card/card.component';
import { QueryService, Table } from '../query.service';
import { randomUuid } from '../uuid';

interface Entry {
  label: string;
  table: Table;
  state: ReturnType<typeof signal<CardState>>;
}

@Component({
  selector: 'app-app-page',
  imports: [CardComponent],
  template: `
    <h2>Current app</h2>
    <p class="subtitle">
      The backend opens a direct JDBC/Mongo connection to each production
      database. This is how your current application already works today.
      ADB 26ai is not involved in this path.
    </p>

    <button (click)="loadAll()" [disabled]="busy()">
      {{ busy() ? 'Loading…' : 'Load banking data' }}
    </button>

    <div class="grid">
      @for (e of entries; track e.table) {
        <app-card [label]="e.label" [state]="e.state()" />
      }
    </div>
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; color: #2C2723; }
    .subtitle { color: #6B6560; margin-bottom: 1.25rem; font-size: 0.9rem; line-height: 1.4; }
    .grid {
      margin-top: 1.5rem;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
      gap: 1rem;
    }
  `,
})
export class AppPageComponent {
  constructor(private query: QueryService) {}

  busy = signal(false);

  entries: Entry[] = [
    { label: 'Oracle Free 26ai — accounts',         table: 'accounts',        state: signal<CardState>({ kind: 'idle' }) },
    { label: 'Oracle Free 26ai — transactions',     table: 'transactions',    state: signal<CardState>({ kind: 'idle' }) },
    { label: 'PostgreSQL 18 — policies',            table: 'policies',        state: signal<CardState>({ kind: 'idle' }) },
    { label: 'PostgreSQL 18 — rules',               table: 'rules',           state: signal<CardState>({ kind: 'idle' }) },
    { label: 'MongoDB 8 — support_tickets',         table: 'support_tickets', state: signal<CardState>({ kind: 'idle' }) },
  ];

  loadAll(): void {
    const runId = randomUuid();
    this.busy.set(true);
    let remaining = this.entries.length;
    for (const e of this.entries) {
      e.state.set({ kind: 'loading' });
      this.query.run(e.table, 'direct', runId).subscribe({
        next: (res) => {
          if (res.error) {
            e.state.set({ kind: 'error', message: res.error, elapsedMs: res.elapsedMs });
          } else {
            e.state.set({ kind: 'success', data: res });
          }
          if (--remaining === 0) this.busy.set(false);
        },
        error: (err) => {
          e.state.set({ kind: 'error', message: err?.error?.message || 'Request failed' });
          if (--remaining === 0) this.busy.set(false);
        },
      });
    }
  }
}

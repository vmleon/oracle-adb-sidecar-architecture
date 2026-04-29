import { Component, computed, inject, signal } from '@angular/core';
import { CardComponent, CardState } from '../card/card.component';
import { QueryService, Table } from '../query.service';
import { ReadinessService } from '../readiness.service';
import { randomUuid } from '../uuid';

interface Entry {
  label: string;
  table: Table;
  state: ReturnType<typeof signal<CardState>>;
  skip?: boolean;
}

@Component({
  selector: 'app-sidecar-page',
  imports: [CardComponent],
  template: `
    <h2>ADB 26ai sidecar (federated)</h2>
    <p class="subtitle">
      The backend issues one JDBC query per table to the ADB 26ai sidecar.
      ADB resolves the V_* views through DB_LINK into Oracle Free and Postgres.
      Your production databases are unchanged; 26ai capabilities layer on top.
    </p>

    <button (click)="loadAll()" [disabled]="busy() || !ready()">
      {{ buttonLabel() }}
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
export class SidecarPageComponent {
  private readiness = inject(ReadinessService);
  constructor(private query: QueryService) {}

  busy = signal(false);
  ready = this.readiness.sidecarReady;
  buttonLabel = computed(() =>
    this.busy() ? 'Loading…' : this.ready() ? 'Load banking data via ADB sidecar' : 'Waiting for ADB sidecar…'
  );

  entries: Entry[] = [
    { label: 'Oracle Free 26ai — accounts',         table: 'accounts',        state: signal<CardState>({ kind: 'idle' }) },
    { label: 'Oracle Free 26ai — transactions',     table: 'transactions',    state: signal<CardState>({ kind: 'idle' }) },
    { label: 'PostgreSQL 18 — policies',            table: 'policies',        state: signal<CardState>({ kind: 'idle' }) },
    { label: 'PostgreSQL 18 — rules',               table: 'rules',           state: signal<CardState>({ kind: 'idle' }) },
    {
      label: 'MongoDB 8 — support_tickets',
      table: 'support_tickets',
      state: signal<CardState>({
        kind: 'disabled',
        reason: 'Not available via sidecar — ADB heterogeneous gateway bug, see docs.',
      }),
      skip: true,
    },
  ];

  loadAll(): void {
    const runId = randomUuid();
    const live = this.entries.filter((e) => !e.skip);
    this.busy.set(true);
    let remaining = live.length;
    for (const e of live) {
      e.state.set({ kind: 'loading' });
      this.query.run(e.table, 'federated', runId).subscribe({
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

import { Component, inject, signal } from '@angular/core';
import { VersionsService, DemoResponse, Row } from '../versions.service';

type EngineKey = keyof DemoResponse;

type Entity = {
  engine: EngineKey;
  key: string;
  errorKey: string;
  label: string;
};

@Component({
  selector: 'app-versions',
  template: `
    <h2>Banking demo — federated across three engines</h2>
    <p class="subtitle">
      Three simulated production databases hold a toy banking dataset:
      Oracle Free 26ai (accounts, transactions), PostgreSQL 18 (policies, rules),
      MongoDB 8 (support tickets). The first button reads each database directly.
      The second reads the same data through the ADB 26ai sidecar via DB_LINK views,
      proving the federated path end-to-end.
    </p>

    <section class="row">
      <h3 class="row-title">Direct (backend → each DB)</h3>
      <button (click)="load()" [disabled]="loading()">
        {{ loading() ? 'Querying...' : 'Load banking demo' }}
      </button>

      @if (error()) {
        <p class="error">{{ error() }}</p>
      }

      @if (direct(); as d) {
        <div class="grid">
          @for (e of entities; track e.engine + ':' + e.key) {
            <article class="card">
              <h4>{{ e.label }}</h4>
              @if (rowsFor(d, e); as rows) {
                <table>
                  <thead>
                    <tr>
                      @for (h of headersFor(rows); track h) {
                        <th>{{ h }}</th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (r of rows; track $index) {
                      <tr>
                        @for (h of headersFor(rows); track h) {
                          <td [class]="cellClass(r, h)">{{ cell(r, h) }}</td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              } @else {
                <p class="error">{{ errorFor(d, e) || 'no data' }}</p>
              }
            </article>
          }
        </div>
      }
    </section>

    <section class="row">
      <h3 class="row-title">Via ADB 26ai sidecar (DB_LINK federated)</h3>
      <button (click)="loadViaSidecar()" [disabled]="sidecarLoading()">
        {{ sidecarLoading() ? 'Querying...' : 'Load banking demo via ADB sidecar' }}
      </button>

      @if (sidecarError()) {
        <p class="error">{{ sidecarError() }}</p>
      }

      @if (sidecar(); as d) {
        <div class="grid">
          @for (e of entities; track e.engine + ':' + e.key) {
            <article class="card">
              <h4>{{ e.label }}</h4>
              @if (rowsFor(d, e); as rows) {
                <table>
                  <thead>
                    <tr>
                      @for (h of headersFor(rows); track h) {
                        <th>{{ h }}</th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (r of rows; track $index) {
                      <tr>
                        @for (h of headersFor(rows); track h) {
                          <td [class]="cellClass(r, h)">{{ cell(r, h) }}</td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              } @else {
                <p class="error">{{ errorFor(d, e) || 'no data' }}</p>
              }
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; color: #2C2723; }
    .subtitle { color: #6B6560; margin-bottom: 1.25rem; font-size: 0.9rem; line-height: 1.4; }
    .row { margin-top: 2rem; }
    .row-title {
      font-family: Georgia, serif;
      font-size: 1.1rem;
      margin: 0 0 0.75rem;
      color: #2C2723;
    }
    .grid {
      margin-top: 1.5rem;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
      gap: 1rem;
    }
    .card {
      background: #FFFFFF;
      border: 1px solid #E5E0DA;
      border-radius: 8px;
      padding: 1rem;
      overflow-x: auto;
      box-shadow: 0 1px 2px rgba(44, 39, 35, 0.04);
    }
    .card h4 {
      margin: 0 0 0.5rem;
      color: #C74634;
      font-size: 0.9rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    th, td {
      text-align: left;
      padding: 0.35rem 0.5rem;
      border-bottom: 1px solid #E5E0DA;
    }
    th { color: #6B6560; font-weight: normal; text-transform: uppercase; font-size: 0.7rem; }
    td.amount-neg { color: #C74634; font-variant-numeric: tabular-nums; }
    td.amount-pos { color: #1A7F3C; font-variant-numeric: tabular-nums; }
    .error { color: #C74634; margin-top: 1rem; font-size: 0.85rem; }
  `,
})
export class VersionsComponent {
  private service = inject(VersionsService);

  loading = signal(false);
  error = signal('');
  direct = signal<DemoResponse | null>(null);

  sidecarLoading = signal(false);
  sidecarError = signal('');
  sidecar = signal<DemoResponse | null>(null);

  entities: Entity[] = [
    { engine: 'oracle',   key: 'accounts',        errorKey: 'accounts_error',     label: 'Oracle Free 26ai — accounts' },
    { engine: 'oracle',   key: 'transactions',    errorKey: 'transactions_error', label: 'Oracle Free 26ai — transactions' },
    { engine: 'postgres', key: 'policies',        errorKey: 'policies_error',     label: 'PostgreSQL 18 — policies' },
    { engine: 'postgres', key: 'rules',           errorKey: 'rules_error',        label: 'PostgreSQL 18 — rules' },
    { engine: 'mongo',    key: 'support_tickets', errorKey: 'error',              label: 'MongoDB 8 — support_tickets' },
  ];

  rowsFor(d: DemoResponse, e: Entity): Row[] | null {
    const section = d[e.engine] as Record<string, unknown> | undefined;
    if (!section) return null;
    const rows = section[e.key];
    return Array.isArray(rows) ? (rows as Row[]) : null;
  }

  errorFor(d: DemoResponse, e: Entity): string | null {
    const section = d[e.engine] as Record<string, unknown> | undefined;
    if (!section) return null;
    const err = section[e.errorKey];
    return typeof err === 'string' ? err : null;
  }

  headersFor(rows: Row[]): string[] {
    if (!rows.length) return [];
    return Object.keys(rows[0]);
  }

  cell(row: Row, header: string): string {
    const v = row[header];
    if (v == null) return '';
    if (typeof v === 'object') return JSON.stringify(v);
    if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(v)) {
      return this.formatDate(v);
    }
    return String(v);
  }

  cellClass(row: Row, header: string): string {
    if (header.toLowerCase() !== 'amount') return '';
    const v = row[header];
    const n = typeof v === 'number' ? v : parseFloat(String(v));
    if (isNaN(n)) return '';
    return n < 0 ? 'amount-neg' : n > 0 ? 'amount-pos' : '';
  }

  private formatDate(iso: string): string {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const date = d.toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC',
    });
    const time = d.toLocaleTimeString('en-US', {
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: 'UTC',
    });
    return `${date} · ${time}`;
  }

  load() {
    this.loading.set(true);
    this.error.set('');
    this.direct.set(null);
    this.service.fetch().subscribe({
      next: (res) => {
        this.direct.set(res);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message || 'Request failed');
        this.loading.set(false);
      },
    });
  }

  loadViaSidecar() {
    this.sidecarLoading.set(true);
    this.sidecarError.set('');
    this.sidecar.set(null);
    this.service.fetchViaSidecar().subscribe({
      next: (res) => {
        this.sidecar.set(res);
        this.sidecarLoading.set(false);
      },
      error: (err) => {
        this.sidecarError.set(err?.error?.message || 'Request failed');
        this.sidecarLoading.set(false);
      },
    });
  }
}

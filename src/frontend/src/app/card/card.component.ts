import { Component, computed, input } from '@angular/core';
import { QueryResponse, Row } from '../query.service';

export type CardState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'success'; data: QueryResponse }
  | { kind: 'error'; message: string; elapsedMs?: number }
  | { kind: 'disabled'; reason: string };

@Component({
  selector: 'app-card',
  template: `
    <article class="card">
      <header class="card-head">
        <h4>{{ label() }}</h4>
        @if (state().kind === 'success') {
          <span class="badge">{{ ms() }} ms</span>
        }
      </header>

      @switch (state().kind) {
        @case ('idle') {
          <p class="muted">—</p>
        }
        @case ('loading') {
          <p class="muted">Loading…</p>
        }
        @case ('disabled') {
          <p class="muted">{{ disabledReason() }}</p>
        }
        @case ('error') {
          <p class="error">{{ errorMessage() }}</p>
        }
        @case ('success') {
          @if (rows().length) {
            <table>
              <thead>
                <tr>
                  @for (h of headers(); track h) {
                    <th>{{ h }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (r of rows(); track $index) {
                  <tr>
                    @for (h of headers(); track h) {
                      <td [class]="cellClass(r, h)">{{ cell(r, h) }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          } @else {
            <p class="muted">no rows</p>
          }
        }
      }
    </article>
  `,
  styles: `
    .card {
      background: #FFFFFF;
      border: 1px solid #E5E0DA;
      border-radius: 8px;
      padding: 1rem;
      overflow-x: auto;
      box-shadow: 0 1px 2px rgba(44, 39, 35, 0.04);
    }
    .card-head { display: flex; justify-content: space-between; align-items: baseline; }
    .card-head h4 {
      margin: 0 0 0.5rem;
      color: #C74634;
      font-size: 0.9rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .badge {
      font-size: 0.75rem;
      color: #6B6560;
      background: #F5F2EE;
      padding: 0.15rem 0.45rem;
      border-radius: 999px;
      font-variant-numeric: tabular-nums;
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
    .muted { color: #6B6560; font-size: 0.85rem; }
    .error { color: #C74634; font-size: 0.85rem; }
  `,
})
export class CardComponent {
  label = input.required<string>();
  state = input.required<CardState>();

  rows = computed<Row[]>(() => {
    const s = this.state();
    return s.kind === 'success' ? s.data.rows : [];
  });

  ms = computed(() => {
    const s = this.state();
    return s.kind === 'success' ? s.data.elapsedMs.toFixed(1) : '';
  });

  errorMessage = computed(() => {
    const s = this.state();
    return s.kind === 'error' ? s.message : '';
  });

  disabledReason = computed(() => {
    const s = this.state();
    return s.kind === 'disabled' ? s.reason : '';
  });

  headers(): string[] {
    const r = this.rows();
    return r.length ? Object.keys(r[0]) : [];
  }

  cell(row: Row, h: string): string {
    const v = row[h];
    if (v == null) return '';
    if (typeof v === 'object') return JSON.stringify(v);
    if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(v)) {
      const d = new Date(v);
      if (!isNaN(d.getTime())) {
        return d.toISOString().replace('T', ' ').replace(/\..+$/, 'Z');
      }
    }
    return String(v);
  }

  cellClass(row: Row, h: string): string {
    if (h.toLowerCase() !== 'amount') return '';
    const v = row[h];
    const n = typeof v === 'number' ? v : parseFloat(String(v));
    if (isNaN(n)) return '';
    return n < 0 ? 'amount-neg' : n > 0 ? 'amount-pos' : '';
  }
}

import { Component, OnInit, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BaseChartDirective } from 'ng2-charts';
import type { ChartConfiguration, ChartData } from 'chart.js';
import {
  Aggregate,
  MeasurementsService,
  SeriesPoint,
} from '../measurements.service';
import { Route } from '../query.service';

interface SummaryRow {
  queryId: string;
  direct: Aggregate | null;
  federated: Aggregate | null;
  deltaMs: number | null;
  deltaPct: number | null;
}

const QUERY_IDS = [
  'oracle.accounts',
  'oracle.transactions',
  'postgres.policies',
  'postgres.rules',
  'mongo.support_tickets',
];

function iqrTrim(values: number[]): number[] {
  if (values.length < 4) return values;
  const sorted = [...values].sort((a, b) => a - b);
  const q = (p: number) => {
    const idx = (sorted.length - 1) * p;
    const lo = Math.floor(idx);
    const hi = Math.ceil(idx);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
  };
  const q1 = q(0.25);
  const q3 = q(0.75);
  const iqr = q3 - q1;
  const lo = q1 - 1.5 * iqr;
  const hi = q3 + 1.5 * iqr;
  return values.filter((v) => v >= lo && v <= hi);
}

@Component({
  selector: 'app-measurements-page',
  imports: [FormsModule, BaseChartDirective],
  template: `
    <h2>Direct vs federated</h2>
    <p class="subtitle">
      Every DB call is timed at the backend boundary (wall-clock,
      <code>System.nanoTime()</code>) and persisted to <code>query_measurements</code>
      in ADB. The INSERT is async and is not counted. Use the outlier toggle to
      strip warm-up runs (IQR × 1.5).
    </p>

    <div class="controls">
      <button (click)="triggerRuns()" [disabled]="triggering()">
        {{ triggering() ? 'Triggering…' : 'Trigger ' + runsToTrigger + ' runs' }}
      </button>
      <label>
        <input type="checkbox" [(ngModel)]="trimOn" (change)="reload()" />
        Trim outliers (IQR)
      </label>
      <button (click)="reload()">Refresh</button>
    </div>

    <h3>Summary</h3>
    <table class="summary">
      <thead>
        <tr>
          <th>Query</th>
          <th colspan="3">Direct</th>
          <th colspan="3">Federated</th>
          <th>Δ mean (ms)</th>
        </tr>
        <tr class="sub">
          <th></th>
          <th class="section">n</th><th>mean</th><th>p95</th>
          <th class="section">n</th><th>mean</th><th>p95</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        @for (r of summary(); track r.queryId) {
          <tr>
            <td>{{ r.queryId }}</td>
            <td class="section">{{ r.direct?.count ?? '—' }}</td>
            <td>{{ fmt(r.direct?.mean) }}</td>
            <td>{{ fmt(r.direct?.p95) }}</td>
            <td class="section">{{ r.federated?.count ?? '—' }}</td>
            <td>{{ fmt(r.federated?.mean) }}</td>
            <td>{{ fmt(r.federated?.p95) }}</td>
            <td [class.pos]="r.deltaMs && r.deltaMs > 0">{{ fmt(r.deltaMs) }}</td>
          </tr>
        }
      </tbody>
    </table>

    <h3>Box plots (direct vs federated per query)</h3>
    <div class="chart-wrap">
      <canvas baseChart
              [type]="boxChartType"
              [data]="boxData()"
              [options]="boxOptions"></canvas>
    </div>
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; color: #2C2723; }
    h3 { font-family: Georgia, serif; color: #2C2723; margin-top: 2rem; }
    .subtitle { color: #6B6560; margin-bottom: 1rem; font-size: 0.9rem; line-height: 1.4; }
    .controls { display: flex; gap: 0.75rem; align-items: center; margin: 0.75rem 0 1.25rem; }
    .summary { width: 100%; border-collapse: collapse; font-size: 0.85rem; background: #FFFFFF; }
    .summary th, .summary td { border-bottom: 1px solid #E5E0DA; padding: 0.35rem 0.5rem; text-align: right; }
    .summary th { color: #6B6560; font-weight: normal; text-transform: uppercase; font-size: 0.7rem; text-align: center; }
    .summary .sub th { font-size: 0.65rem; }
    .summary tbody td:first-child { text-align: left; font-family: monospace; }
    .summary td.pos { color: #C74634; font-variant-numeric: tabular-nums; }
    .summary .section { background: #F5F2EE; border-left: 1px solid #E5E0DA; }
    .chart-wrap { background: #FFFFFF; border: 1px solid #E5E0DA; border-radius: 8px; padding: 1rem; height: 360px; }
  `,
})
export class MeasurementsPageComponent implements OnInit {
  runsToTrigger = 20;
  trimOn = true;
  triggering = signal(false);
  aggregates = signal<Aggregate[]>([]);
  points = signal<Record<string, SeriesPoint[]>>({});
  boxChartType = 'boxplot' as any;

  summary = computed<SummaryRow[]>(() => {
    const byKey = new Map<string, Aggregate>();
    for (const a of this.aggregates()) {
      byKey.set(`${a.queryId}|${a.route}`, a);
    }
    return QUERY_IDS.map((q) => {
      const d = byKey.get(`${q}|direct`) ?? null;
      const f = byKey.get(`${q}|federated`) ?? null;
      let deltaMs: number | null = null;
      let deltaPct: number | null = null;
      if (d && f) {
        deltaMs = f.mean - d.mean;
        deltaPct = d.mean ? (deltaMs / d.mean) * 100 : null;
      }
      return { queryId: q, direct: d, federated: f, deltaMs, deltaPct };
    });
  });

  boxOptions: ChartConfiguration<'scatter'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    scales: { y: { title: { display: true, text: 'ms' }, beginAtZero: true } },
  };

  boxData = computed<ChartData<any>>(() => {
    const series = this.points();
    const trim = this.trimOn ? iqrTrim : (v: number[]) => v;
    const direct = QUERY_IDS.map((q) =>
      trim((series[`${q}|direct`] ?? []).map((p) => p.elapsedMs)),
    );
    const federated = QUERY_IDS.map((q) =>
      trim((series[`${q}|federated`] ?? []).map((p) => p.elapsedMs)),
    );
    return {
      labels: QUERY_IDS,
      datasets: [
        { label: 'direct',    data: direct,    backgroundColor: 'rgba(26, 127, 60, 0.3)', borderColor: '#1A7F3C' },
        { label: 'federated', data: federated, backgroundColor: 'rgba(199, 70, 52, 0.3)', borderColor: '#C74634' },
      ],
    };
  });

  constructor(private svc: MeasurementsService) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.svc.aggregate(this.trimOn ? 'iqr' : 'none').subscribe((a) => this.aggregates.set(a));
    for (const q of QUERY_IDS) {
      for (const r of ['direct', 'federated'] as Route[]) {
        if (q === 'mongo.support_tickets' && r === 'federated') continue;
        this.svc.series(q, r, 200).subscribe((pts) => {
          const current = this.points();
          this.points.set({ ...current, [`${q}|${r}`]: pts });
        });
      }
    }
  }

  triggerRuns(): void {
    this.triggering.set(true);
    this.svc.triggerRounds(this.runsToTrigger).subscribe({
      next: () => { this.triggering.set(false); this.reload(); },
      error: () => { this.triggering.set(false); },
    });
  }

  fmt(n: number | null | undefined): string {
    if (n == null) return '—';
    return n.toFixed(2);
  }
}

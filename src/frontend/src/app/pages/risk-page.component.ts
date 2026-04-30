import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BaseChartDirective } from 'ng2-charts';
import type { ChartConfiguration, ChartData } from 'chart.js';
import { ReadinessService } from '../readiness.service';
import { RiskDashboard, RiskService } from '../risk.service';

const SANCTIONED = new Set(['BY', 'IR', 'KP', 'RU', 'SY', 'VE', 'MM', 'CU']);

const COUNTRY_NAMES: Record<string, string> = {
  BY: 'Belarus', IR: 'Iran', KP: 'North Korea', RU: 'Russia',
  SY: 'Syria', VE: 'Venezuela', MM: 'Myanmar', CU: 'Cuba',
  US: 'United States', GB: 'United Kingdom', DE: 'Germany',
  IT: 'Italy', JP: 'Japan', FR: 'France', SG: 'Singapore',
  AE: 'United Arab Emirates', SE: 'Sweden', IE: 'Ireland',
  MX: 'Mexico', AR: 'Argentina', IN: 'India', NG: 'Nigeria',
  BG: 'Bulgaria',
};

function countryName(code: string): string {
  return COUNTRY_NAMES[code] ?? code;
}

@Component({
  selector: 'app-risk-page',
  imports: [BaseChartDirective, RouterLink],
  template: `
    <h2>Risk Dashboard</h2>
    <p class="subtitle">
      Compliance & risk overview built from the same data as the
      <a routerLink="/app">Current System</a> route — customers, accounts,
      transactions (Oracle Free), policies and rules (PostgreSQL), and
      support tickets (MongoDB). Each chart cites the rule codes that drive it.
    </p>

    @if (!riskReady() && !data()) {
      <section class="placeholder">
        <h3>Risk Dashboard is bootstrapping</h3>
        <p>
          Waiting for the production databases (Oracle Free, PostgreSQL,
          MongoDB) and the rich banking schema to be in place. This typically
          clears within a few minutes after first deploy. The status pill in
          the top-right shows live readiness; this view auto-refreshes once
          everything is up.
        </p>
      </section>
    } @else if (loading()) {
      <p class="loading">Loading…</p>
    } @else if (error()) {
      <p class="error">Could not load dashboard: {{ error() }}</p>
    } @else if (data(); as d) {
      <section class="kpis">
        <div class="kpi"><div class="num">{{ d.kpis.kycAttention }}</div><div class="lbl">KYC attention</div><div class="cap">PENDING + EXPIRED</div></div>
        <div class="kpi"><div class="num">{{ d.kpis.frozenAccounts }}</div><div class="lbl">Frozen accounts</div><div class="cap">status = FROZEN</div></div>
        <div class="kpi"><div class="num">{{ d.kpis.highRiskCustomers }}</div><div class="lbl">High-risk customers</div><div class="cap">internal risk tier</div></div>
        <div class="kpi"><div class="num">{{ d.kpis.subCtrActivity }}</div><div class="lbl">Sub-CTR activity</div><div class="cap">|amount| in $9,000–$9,999</div></div>
        <div class="kpi"><div class="num">{{ d.kpis.declineVelocity }}</div><div class="lbl">Decline velocity</div><div class="cap">≥3 declines / 1 h</div></div>
        <div class="kpi"><div class="num">{{ d.kpis.openHighPriorityTickets }}</div><div class="lbl">Open HIGH tickets</div><div class="cap">customer-care queue</div></div>
      </section>

      <section class="card">
        <h3>1 · Sub-CTR activity watchlist</h3>
        <div class="chart-wrap">
          <canvas baseChart [type]="'bar'" [data]="subCtrData()" [options]="subCtrOpts"></canvas>
        </div>
        <p class="footer">
          Cash deposits and outbound wires sized just below $10,000 are a classic
          indicator of "structuring" — splitting a single large transaction into
          several smaller ones to stay under the Currency Transaction Report
          threshold (policy <code>P-CTR-01</code>). Three or more such transactions
          on one account inside a 7-day window trip <code>R-AML-005</code> and
          warrant a Suspicious Activity Report under <code>P-SAR-01</code>.
        </p>
      </section>

      <section class="card">
        <h3>2 · Cross-border wire flows</h3>
        @if (d.crossBorderWires.length === 0) {
          <p class="empty">No outbound international wires in the current dataset.</p>
        } @else {
          <table class="data">
            <thead>
              <tr><th>Country</th><th>Code</th><th>Status</th><th class="num">Wires</th><th class="num">Total |amount|</th></tr>
            </thead>
            <tbody>
              @for (r of d.crossBorderWires; track r.country) {
                <tr [class.flag]="isSanctioned(r.country)">
                  <td>{{ name(r.country) }}</td>
                  <td><code>{{ r.country }}</code></td>
                  <td>
                    @if (isSanctioned(r.country)) {
                      <span class="badge violation">OFAC sanctioned</span>
                    } @else {
                      <span class="badge ok">Permitted</span>
                    }
                  </td>
                  <td class="num">{{ r.txnCount }}</td>
                  <td class="num">{{ money(r.totalAmount) }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        <p class="footer">
          Outbound <code>WIRE</code> transactions grouped by destination country.
          Counterparties in jurisdictions sanctioned by the U.S. Office of
          Foreign Assets Control (OFAC) — Belarus (BY), Iran (IR), North Korea
          (KP), Russia (RU), Syria (SY), and others — trip <code>R-OFAC-001</code>
          and must be blocked or held for Specially Designated Nationals (SDN)
          screening under policy <code>P-OFAC-01</code>. International wires
          above $10,000 also require senior-officer approval per
          <code>R-WIRE-001</code>.
        </p>
      </section>

      <section class="card">
        <h3>3 · Decline velocity timeline</h3>
        @if (d.declineEvents.length === 0) {
          <p class="empty">No declined card authorisations in the current dataset.</p>
        } @else {
          <div class="chart-wrap">
            <canvas baseChart [type]="'scatter'" [data]="declineData()" [options]="declineOpts()"></canvas>
          </div>
        }
        <p class="footer">
          Each point is a card authorisation; red points are declines. Three or
          more declined authorisations on the same account inside a one-hour
          window trip <code>R-FRAUD-007</code> ("Velocity-triggered freeze"), an
          auto-freeze rule designed to stop card-testing fraud where a
          compromised card number is probed across merchants and currencies
          until one succeeds.
        </p>
      </section>

      <section class="card">
        <h3>4 · KYC pipeline</h3>
        <div class="kyc-row">
          <div class="chart-wrap small">
            <canvas baseChart [type]="'doughnut'" [data]="kycData()" [options]="kycOpts"></canvas>
          </div>
          <div class="kyc-list">
            <h4>Non-verified customers</h4>
            @if (d.kycPipeline.nonVerified.length === 0) {
              <p class="empty">All customers are VERIFIED.</p>
            } @else {
              <table class="data slim">
                <thead><tr><th>Name</th><th>Country</th><th>Status</th><th>Risk</th><th>Joined</th></tr></thead>
                <tbody>
                  @for (c of d.kycPipeline.nonVerified; track c.id) {
                    <tr>
                      <td>{{ c.name }}</td>
                      <td><code>{{ c.country }}</code></td>
                      <td><span class="badge" [class.violation]="c.kycStatus === 'EXPIRED'" [class.warn]="c.kycStatus === 'PENDING'">{{ c.kycStatus }}</span></td>
                      <td>{{ c.riskTier }}</td>
                      <td>{{ c.joinedAt }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>
        <p class="footer">
          Every customer must complete and periodically refresh Customer
          Identification Programme (KYC, "Know Your Customer") checks under
          policy <code>P-KYC-01</code>. The "EXPIRED" segment has lapsed
          documentation (<code>R-KYC-001</code>) and cannot be sold new high-risk
          products until refreshed; the "PENDING" segment is still in onboarding.
          HIGH-risk customers must additionally re-verify every three years
          under <code>R-KYC-002</code>.
        </p>
      </section>

      <section class="card">
        <h3>5 · Risk tier × account status</h3>
        <div class="chart-wrap">
          <canvas baseChart [type]="'bar'" [data]="riskByStatusData()" [options]="riskByStatusOpts"></canvas>
        </div>
        <p class="footer">
          Risk tier (LOW / MEDIUM / HIGH) is the bank's internal customer rating
          that sizes due-diligence intensity; account status is the operational
          state. Two cells warrant attention: HIGH-risk + ACTIVE accounts need
          enhanced ongoing monitoring, and any LOW-risk + FROZEN account should
          be reviewed — a freeze on a low-risk customer usually indicates a
          transactional rule like <code>R-FRAUD-007</code> fired rather than a
          relationship concern.
        </p>
      </section>

      <section class="card">
        <h3>6 · Tickets by priority over time</h3>
        @if (d.ticketsByPriority.length === 0) {
          <p class="empty">No prioritised support tickets in the current dataset.</p>
        } @else {
          <div class="chart-wrap">
            <canvas baseChart [type]="'bar'" [data]="ticketsData()" [options]="ticketsOpts"></canvas>
          </div>
        }
        <p class="footer">
          Daily customer-care ticket volume, stacked by priority. HIGH-priority
          tickets covering disputed or unauthorised electronic transfers fall
          under Regulation E: provisional credit must be issued within 10
          business days (<code>R-REGE-001</code>) and the dispute filing window
          is 60 days (<code>R-REGE-002</code>). A growing HIGH band typically
          signals an emerging fraud pattern or a payment-rail incident.
        </p>
      </section>

      <section class="card">
        <h3>7 · Active rule violations</h3>
        <table class="data">
          <thead>
            <tr><th>Code</th><th>Severity</th><th>Rule</th><th>Policy</th><th class="num">Count</th></tr>
          </thead>
          <tbody>
            @for (r of d.rules; track r.code) {
              <tr [class.flag]="(r.violationCount ?? 0) > 0 && r.severity === 'VIOLATION'">
                <td><code>{{ r.code }}</code></td>
                <td>
                  <span class="badge"
                        [class.violation]="r.severity === 'VIOLATION'"
                        [class.warn]="r.severity === 'WARNING'"
                        [class.info]="r.severity === 'INFO'">{{ r.severity }}</span>
                </td>
                <td>
                  <strong>{{ r.name }}</strong>
                  <div class="desc">{{ r.description }}</div>
                </td>
                <td><code>{{ r.policyCode }}</code></td>
                <td class="num">{{ r.violationCount === null ? '—' : r.violationCount }}</td>
              </tr>
            }
          </tbody>
        </table>
        <p class="footer">
          A live count of how many customers or transactions in the current
          dataset trip each compliance rule, ordered by severity. VIOLATION-severity
          rules require immediate action (case file, hold, or regulatory
          filing); WARNING-severity rules require review with an audit trail.
          A dash means the rule is monitored but not evaluated against the
          current dataset.
        </p>
      </section>
    }
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; color: #2C2723; }
    h3 { font-family: Georgia, serif; color: #2C2723; margin: 0 0 0.75rem; font-size: 1.05rem; }
    h4 { font-family: Georgia, serif; color: #2C2723; margin: 0 0 0.5rem; font-size: 0.95rem; }
    .subtitle { color: #6B6560; margin-bottom: 1.25rem; font-size: 0.9rem; line-height: 1.4; }
    .subtitle a { color: #C74634; }
    .loading, .error, .empty { color: #6B6560; font-size: 0.9rem; padding: 0.5rem 0; }
    .error { color: #C74634; }
    .placeholder {
      background: #FFFFFF;
      border: 1px dashed #C9C2BA;
      border-radius: 8px;
      padding: 1.25rem 1.5rem;
      color: #4A453F;
    }
    .placeholder h3 { margin: 0 0 0.4rem; font-family: Georgia, serif; color: #2C2723; font-size: 1.05rem; }
    .placeholder p { margin: 0; font-size: 0.9rem; line-height: 1.5; }

    .kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 0.75rem;
      margin-bottom: 1.5rem;
    }
    .kpi {
      background: #FFFFFF;
      border: 1px solid #E5E0DA;
      border-radius: 8px;
      padding: 0.85rem 1rem;
    }
    .kpi .num { font-size: 1.8rem; font-weight: 600; color: #2C2723; line-height: 1; }
    .kpi .lbl { font-size: 0.85rem; color: #2C2723; margin-top: 0.25rem; }
    .kpi .cap { font-size: 0.7rem; color: #9B9590; margin-top: 0.15rem; }

    .card {
      background: #FFFFFF;
      border: 1px solid #E5E0DA;
      border-radius: 8px;
      padding: 1rem 1.25rem 1.25rem;
      margin-bottom: 1.5rem;
    }
    .chart-wrap { height: 280px; margin-bottom: 0.75rem; }
    .chart-wrap.small { height: 220px; flex: 0 0 240px; }

    .kyc-row { display: flex; gap: 1.5rem; align-items: flex-start; flex-wrap: wrap; }
    .kyc-list { flex: 1; min-width: 280px; }

    .data { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
    .data th, .data td {
      border-bottom: 1px solid #E5E0DA;
      padding: 0.4rem 0.5rem;
      text-align: left;
      vertical-align: top;
    }
    .data th {
      color: #6B6560; font-weight: normal; text-transform: uppercase;
      font-size: 0.7rem; background: #F5F2EE;
    }
    .data td.num, .data th.num { text-align: right; font-variant-numeric: tabular-nums; }
    .data tr.flag { background: #FDF3F1; }
    .data .desc { color: #6B6560; font-size: 0.78rem; margin-top: 0.15rem; line-height: 1.35; }
    .data.slim th, .data.slim td { padding: 0.3rem 0.4rem; font-size: 0.8rem; }
    code { background: #F5F2EE; padding: 0.05rem 0.3rem; border-radius: 3px; font-size: 0.78rem; }

    .badge {
      display: inline-block; padding: 0.1rem 0.45rem; border-radius: 3px;
      font-size: 0.7rem; font-weight: 600; letter-spacing: 0.02em;
      background: #E5E0DA; color: #2C2723;
    }
    .badge.violation { background: #C74634; color: #FFFFFF; }
    .badge.warn      { background: #E89A3C; color: #2C2723; }
    .badge.info      { background: #4A7FB5; color: #FFFFFF; }
    .badge.ok        { background: #1A7F3C; color: #FFFFFF; }

    .footer {
      margin: 0.75rem 0 0;
      padding-top: 0.75rem;
      border-top: 1px dashed #E5E0DA;
      color: #4A453F;
      font-size: 0.82rem;
      line-height: 1.5;
    }
  `,
})
export class RiskPageComponent {
  private svc = inject(RiskService);
  private readiness = inject(ReadinessService);

  data = signal<RiskDashboard | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  riskReady = this.readiness.riskReady;

  private fetched = false;

  constructor() {
    effect(() => {
      if (this.readiness.riskReady() && !this.fetched) {
        this.fetched = true;
        this.loading.set(true);
        this.svc.load().subscribe({
          next: (d) => { this.data.set(d); this.loading.set(false); },
          error: (e) => { this.error.set(e?.message ?? 'request failed'); this.loading.set(false); },
        });
      }
    });
  }

  isSanctioned(code: string): boolean { return SANCTIONED.has(code); }
  name(code: string): string { return countryName(code); }
  money(n: number): string {
    return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(n);
  }

  // Chart 1 — sub-CTR watchlist (stacked bar by customer)
  subCtrData = computed<ChartData<'bar'>>(() => {
    const rows = this.data()?.subCtrWatchlist ?? [];
    return {
      labels: rows.map((r) => r.customer),
      datasets: [
        { label: 'Cash (ATM/branch)', data: rows.map((r) => r.cashCount),
          backgroundColor: 'rgba(199, 70, 52, 0.8)' },
        { label: 'Wire',              data: rows.map((r) => r.wireCount),
          backgroundColor: 'rgba(232, 154, 60, 0.8)' },
        { label: 'Other',             data: rows.map((r) => r.otherCount),
          backgroundColor: 'rgba(155, 149, 144, 0.6)' },
      ],
    };
  });
  subCtrOpts: ChartConfiguration<'bar'>['options'] = {
    indexAxis: 'y',
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      x: { stacked: true, title: { display: true, text: 'Transactions $9,000–$9,999' }, ticks: { stepSize: 1 } },
      y: { stacked: true },
    },
    plugins: { legend: { position: 'bottom' } },
  };

  // Chart 3 — decline velocity scatter (x = time, y = customer)
  declineData = computed<ChartData<'scatter'>>(() => {
    const events = this.data()?.declineEvents ?? [];
    const customers = Array.from(new Set(events.map((e) => e.customer)));
    return {
      labels: customers,
      datasets: [{
        label: 'Declined card auths',
        data: events.map((e) => ({
          x: new Date(e.occurredAt).getTime(),
          y: customers.indexOf(e.customer),
        })),
        backgroundColor: 'rgba(199, 70, 52, 0.85)',
        pointRadius: 6,
      }],
    };
  });
  declineOpts = computed<ChartConfiguration<'scatter'>['options']>(() => {
    const customers = Array.from(new Set((this.data()?.declineEvents ?? []).map((e) => e.customer)));
    return {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: { type: 'time', title: { display: true, text: 'When' } },
        y: {
          type: 'linear',
          min: -0.5,
          max: Math.max(0.5, customers.length - 0.5),
          ticks: {
            stepSize: 1,
            callback: (v) => customers[v as number] ?? '',
          },
          title: { display: true, text: 'Customer' },
        },
      },
      plugins: { legend: { display: false } },
    };
  });

  // Chart 4 — KYC donut
  kycData = computed<ChartData<'doughnut'>>(() => {
    const counts = this.data()?.kycPipeline.counts ?? [];
    const palette: Record<string, string> = {
      VERIFIED: '#1A7F3C', PENDING: '#E89A3C', EXPIRED: '#C74634',
    };
    return {
      labels: counts.map((c) => c.status),
      datasets: [{
        data: counts.map((c) => c.count),
        backgroundColor: counts.map((c) => palette[c.status] ?? '#9B9590'),
      }],
    };
  });
  kycOpts: ChartConfiguration<'doughnut'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { position: 'bottom' } },
  };

  // Chart 5 — risk tier × account status (stacked bar)
  riskByStatusData = computed<ChartData<'bar'>>(() => {
    const rows = this.data()?.riskByStatus ?? [];
    const tiers = ['LOW', 'MEDIUM', 'HIGH'];
    const statuses = Array.from(new Set(rows.map((r) => r.accountStatus))).sort();
    const palette: Record<string, string> = {
      ACTIVE: 'rgba(26, 127, 60, 0.8)', FROZEN: 'rgba(199, 70, 52, 0.8)',
    };
    return {
      labels: tiers,
      datasets: statuses.map((s) => ({
        label: s,
        backgroundColor: palette[s] ?? 'rgba(155, 149, 144, 0.6)',
        data: tiers.map((t) =>
          rows.filter((r) => r.riskTier === t && r.accountStatus === s)
              .reduce((acc, r) => acc + r.count, 0)),
      })),
    };
  });
  riskByStatusOpts: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      x: { stacked: true, title: { display: true, text: 'Risk tier' } },
      y: { stacked: true, title: { display: true, text: 'Accounts' }, ticks: { stepSize: 1 } },
    },
    plugins: { legend: { position: 'bottom' } },
  };

  // Chart 6 — tickets by priority over time (stacked bar)
  ticketsData = computed<ChartData<'bar'>>(() => {
    const rows = this.data()?.ticketsByPriority ?? [];
    const dates = Array.from(new Set(rows.map((r) => r.date))).sort();
    const priorities = ['HIGH', 'MED', 'LOW'];
    const palette: Record<string, string> = {
      HIGH: 'rgba(199, 70, 52, 0.85)',
      MED:  'rgba(232, 154, 60, 0.85)',
      LOW:  'rgba(74, 127, 181, 0.7)',
    };
    return {
      labels: dates,
      datasets: priorities.map((p) => ({
        label: p,
        backgroundColor: palette[p],
        data: dates.map((d) =>
          rows.filter((r) => r.date === d && r.priority === p)
              .reduce((acc, r) => acc + r.count, 0)),
      })),
    };
  });
  ticketsOpts: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      x: { stacked: true, title: { display: true, text: 'Date' } },
      y: { stacked: true, title: { display: true, text: 'Tickets' }, ticks: { stepSize: 1 } },
    },
    plugins: { legend: { position: 'bottom' } },
  };
}

import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ReadinessService } from '../readiness.service';
import { CycleRow, FanoutRow, FraudPatterns, FraudService, StructuringRow } from '../fraud.service';
import { CrossBorderRow, RiskDashboard, RiskService } from '../risk.service';

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
  selector: 'app-fraud-page',
  imports: [RouterLink],
  template: `
    <h2>Fraud Dashboard</h2>
    <p class="subtitle">
      Pattern-level fraud signals from the SQL Property Graph on ADB
      (<code>banking_graph</code> over <code>local_accounts</code> ↔
      <code>transaction_edges</code>) plus cross-border wire flows from the
      production-side <a routerLink="/risk">risk view</a>.
    </p>

    @if (!ready() && !patterns()) {
      <section class="placeholder">
        <h3>Fraud Dashboard is bootstrapping</h3>
        <p>Waiting for ADB and the production databases. Auto-refreshes when ready.</p>
      </section>
    } @else if (loading()) {
      <p class="loading">Loading…</p>
    } @else if (error()) {
      <p class="error">Could not load: {{ error() }}</p>
    } @else if (patterns(); as p) {
      <section class="card">
        <h3>1 · Round-trip cycles
          <span class="count" [class.flag]="p.cycles.length > 0">{{ p.cycles.length }}</span>
        </h3>
        @if (p.cycles.length === 0) {
          <p class="empty">No 3-cycles detected in <code>banking_graph</code>.</p>
        } @else {
          @for (c of p.cycles; track c.aId + '-' + c.bId + '-' + c.cId) {
            <div class="cycle">
              <svg viewBox="0 0 240 200" class="triangle">
                <defs>
                  <marker id="arrow-{{ c.aId }}-{{ c.bId }}-{{ c.cId }}"
                          viewBox="0 0 10 10" refX="10" refY="5"
                          markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                    <path d="M0,0 L10,5 L0,10 z" fill="#C74634"/>
                  </marker>
                </defs>
                <!-- edges -->
                <line x1="120" y1="40"  x2="200" y2="160"
                      stroke="#C74634" stroke-width="2"
                      [attr.marker-end]="'url(#arrow-' + c.aId + '-' + c.bId + '-' + c.cId + ')'"/>
                <line x1="200" y1="160" x2="40"  y2="160"
                      stroke="#C74634" stroke-width="2"
                      [attr.marker-end]="'url(#arrow-' + c.aId + '-' + c.bId + '-' + c.cId + ')'"/>
                <line x1="40"  y1="160" x2="120" y2="40"
                      stroke="#C74634" stroke-width="2"
                      [attr.marker-end]="'url(#arrow-' + c.aId + '-' + c.bId + '-' + c.cId + ')'"/>
                <!-- nodes -->
                <circle cx="120" cy="40"  r="18" fill="#FDF3F1" stroke="#C74634" stroke-width="2"/>
                <circle cx="200" cy="160" r="18" fill="#FDF3F1" stroke="#C74634" stroke-width="2"/>
                <circle cx="40"  cy="160" r="18" fill="#FDF3F1" stroke="#C74634" stroke-width="2"/>
                <text x="120" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2C2723">{{ c.aId }}</text>
                <text x="200" y="164" text-anchor="middle" font-size="11" font-weight="600" fill="#2C2723">{{ c.bId }}</text>
                <text x="40"  y="164" text-anchor="middle" font-size="11" font-weight="600" fill="#2C2723">{{ c.cId }}</text>
              </svg>
              <div class="cycle-meta">
                <div><strong>{{ c.aName }}</strong> (#{{ c.aId }})
                  → <strong>{{ c.bName }}</strong> (#{{ c.bId }})
                  → <strong>{{ c.cName }}</strong> (#{{ c.cId }})
                  → <strong>{{ c.aName }}</strong></div>
                <div class="meta-row">
                  <span>amount: <strong>{{ money(c.amount) }}</strong> per leg</span>
                  <span class="badge violation">risk {{ c.riskScore }}</span>
                </div>
              </div>
            </div>
          }
        }
        <p class="footer">
          A → B → C → A ring transfers with similar amounts and tight timestamps
          are a classic layering pattern: funds round-trip back to origin to
          obscure source. Detected via
          <code>GRAPH_TABLE (banking_graph MATCH (a)-[t1]-&gt;(b)-[t2]-&gt;(c)-[t3]-&gt;(a))</code>;
          canonicalised so each underlying triangle emits once.
        </p>
      </section>

      <section class="card">
        <h3>2 · Fan-out
          <span class="count" [class.flag]="p.fanout.length > 0">{{ p.fanout.length }}</span>
        </h3>
        @if (p.fanout.length === 0) {
          <p class="empty">No accounts with high distinct-destination count.</p>
        } @else {
          <table class="data">
            <thead>
              <tr><th>Account</th><th class="num">Distinct destinations</th><th class="num">Risk</th></tr>
            </thead>
            <tbody>
              @for (f of p.fanout; track f.srcId) {
                <tr class="flag">
                  <td><strong>{{ f.srcName }}</strong> (#{{ f.srcId }})</td>
                  <td class="num">{{ f.fanout }}</td>
                  <td class="num"><span class="badge violation">{{ f.riskScore }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        }
        <p class="footer">
          A single source account pushing funds to many distinct destinations in
          a short window often indicates account takeover or money-mule
          orchestration. Threshold: ≥5 distinct destinations.
        </p>
      </section>

      <section class="card">
        <h3>3 · Structuring (sub-CTR pattern)
          <span class="count" [class.flag]="p.structuring.length > 0">{{ p.structuring.length }}</span>
        </h3>
        @if (p.structuring.length === 0) {
          <p class="empty">No structuring patterns detected.</p>
        } @else {
          <table class="data">
            <thead>
              <tr><th>Account</th><th class="num">Sub-$10K transfers</th><th class="num">Total moved</th><th class="num">Risk</th></tr>
            </thead>
            <tbody>
              @for (s of p.structuring; track s.srcId) {
                <tr class="flag">
                  <td><strong>{{ s.srcName }}</strong> (#{{ s.srcId }})</td>
                  <td class="num">{{ s.transferCount }}</td>
                  <td class="num">{{ money(s.totalAmount) }}</td>
                  <td class="num"><span class="badge violation">{{ s.riskScore }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        }
        <p class="footer">
          Multiple transfers in the $8,000–$9,999 band sized just under the
          $10,000 Currency Transaction Report threshold. Threshold: ≥3 such
          transfers totalling ≥$25,000. Maps to rule
          <code>R-AML-005</code> at the graph level (the
          <a routerLink="/risk">Risk Dashboard</a> shows the per-customer
          transaction-level view of the same signal).
        </p>
      </section>

      <section class="card">
        <h3>4 · Cross-border wire flows</h3>
        @if (crossBorder().length === 0) {
          <p class="empty">No outbound international wires in the current dataset.</p>
        } @else {
          <table class="data">
            <thead>
              <tr><th>Country</th><th>Code</th><th>Status</th><th class="num">Wires</th><th class="num">Total |amount|</th></tr>
            </thead>
            <tbody>
              @for (r of crossBorder(); track r.country) {
                <tr [class.flag]="r.sanctioned">
                  <td>{{ name(r.country) }}</td>
                  <td><code>{{ r.country }}</code></td>
                  <td>
                    @if (r.sanctioned) {
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
          Counterparties in OFAC-sanctioned jurisdictions trip
          <code>R-OFAC-001</code> and must be blocked under
          <code>P-OFAC-01</code>. Moved here from the Risk Dashboard since
          this is fundamentally a fraud / sanctions signal rather than a
          prudential KPI.
        </p>
      </section>
    }
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; color: #2C2723; }
    h3 { font-family: Georgia, serif; color: #2C2723; margin: 0 0 0.75rem; font-size: 1.05rem; display: flex; align-items: center; gap: 0.5rem; }
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
    .placeholder h3 { margin: 0 0 0.4rem; font-size: 1.05rem; }
    .placeholder p { margin: 0; font-size: 0.9rem; line-height: 1.5; }

    .card {
      background: #FFFFFF;
      border: 1px solid #E5E0DA;
      border-radius: 8px;
      padding: 1rem 1.25rem 1.25rem;
      margin-bottom: 1.5rem;
    }
    .count {
      display: inline-flex; justify-content: center; align-items: center;
      min-width: 1.5rem; height: 1.5rem; padding: 0 0.4rem;
      border-radius: 999px; background: #E5E0DA; color: #2C2723;
      font-size: 0.78rem; font-weight: 600; font-family: system-ui;
    }
    .count.flag { background: #C74634; color: #FFFFFF; }

    .cycle {
      display: flex; gap: 1.25rem; align-items: center;
      padding: 0.75rem 0;
      border-bottom: 1px dashed #E5E0DA;
    }
    .cycle:last-child { border-bottom: none; }
    .triangle { flex: 0 0 240px; height: 200px; }
    .cycle-meta { flex: 1; min-width: 220px; font-size: 0.9rem; line-height: 1.5; }
    .meta-row {
      display: flex; gap: 0.75rem; align-items: center;
      margin-top: 0.4rem; color: #4A453F; font-size: 0.85rem;
    }

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
    code { background: #F5F2EE; padding: 0.05rem 0.3rem; border-radius: 3px; font-size: 0.78rem; }

    .badge {
      display: inline-block; padding: 0.1rem 0.45rem; border-radius: 3px;
      font-size: 0.7rem; font-weight: 600; letter-spacing: 0.02em;
      background: #E5E0DA; color: #2C2723;
    }
    .badge.violation { background: #C74634; color: #FFFFFF; }
    .badge.ok        { background: #1A7F3C; color: #FFFFFF; }

    .footer {
      margin: 0.75rem 0 0;
      padding-top: 0.75rem;
      border-top: 1px dashed #E5E0DA;
      color: #4A453F;
      font-size: 0.82rem;
      line-height: 1.5;
    }
    .footer a { color: #C74634; }
  `,
})
export class FraudPageComponent {
  private fraud = inject(FraudService);
  private risk = inject(RiskService);
  private readiness = inject(ReadinessService);

  patterns = signal<FraudPatterns | null>(null);
  riskData = signal<RiskDashboard | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  ready = this.readiness.riskReady;

  crossBorder = computed<CrossBorderRow[]>(() =>
    this.riskData()?.crossBorderWires ?? [],
  );

  private fetched = false;

  constructor() {
    effect(() => {
      if (this.ready() && !this.fetched) {
        this.fetched = true;
        this.loading.set(true);
        this.fraud.load().subscribe({
          next: (p) => {
            this.patterns.set(p);
            this.loading.set(false);
          },
          error: (e) => {
            this.error.set(e?.message ?? 'request failed');
            this.loading.set(false);
          },
        });
        this.risk.load().subscribe({
          next: (r) => this.riskData.set(r),
        });
      }
    });
  }

  name(code: string): string {
    return countryName(code);
  }

  money(n: number): string {
    return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(n);
  }
}

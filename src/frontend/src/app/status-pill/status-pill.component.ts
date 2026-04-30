import { Component, computed, inject } from '@angular/core';
import { ComponentName, ComponentState, ReadinessService } from '../readiness.service';

// Display order: Risk dashboard first (default landing page), then the
// production data sources, then the ADB sidecar, and the Agents team last.
// The `rows` computed below iterates Object.keys in insertion order.
const LABELS: Record<ComponentName, string> = {
  riskDashboard: 'Risk dashboard',
  oracleFree: 'Oracle Free',
  postgres: 'PostgreSQL',
  mongo: 'MongoDB',
  adb: 'ADB sidecar',
  agentsTeam: 'Agents team',
};

// User-facing wording. "Offline" is intentionally vague — we cannot tell
// from a failed probe whether the component is broken or still being
// provisioned, so a softer label avoids alarming the user on cold start.
const STATE_LABELS: Record<ComponentState, string> = {
  ready: 'Ready',
  bootstrapping: 'Bootstrapping',
  error: 'Offline',
};

@Component({
  selector: 'app-status-pill',
  template: `
    <div class="pill">
      <span [class]="'dot ' + overall()"></span>
      <span class="label">Status</span>
      <div class="popup">
        @for (row of rows(); track row.key) {
          <div class="row">
            <span [class]="'dot ' + row.state"></span>
            <span class="name">{{ row.label }}</span>
            <span class="state">{{ stateLabel(row.state) }}</span>
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    .pill {
      position: relative;
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.25rem 0.7rem;
      border-radius: 999px;
      background: #3C3835;
      color: #F1EFED;
      font-size: 0.8rem;
      cursor: default;
      user-select: none;
    }
    .dot {
      display: inline-block;
      width: 0.55rem; height: 0.55rem;
      border-radius: 50%;
      background: #9B9590;
    }
    .dot.ready { background: #1A7F3C; }
    .dot.bootstrapping { background: #E0A030; }
    .dot.error { background: #C74634; }
    .popup {
      position: absolute;
      top: calc(100% + 0.4rem);
      right: 0;
      min-width: 16rem;
      background: #FFFFFF;
      color: #2C2723;
      border: 1px solid #E5E0DA;
      border-radius: 6px;
      padding: 0.5rem;
      box-shadow: 0 4px 12px rgba(0,0,0,0.12);
      display: none;
      z-index: 10;
    }
    .pill:hover .popup, .pill:focus-within .popup { display: block; }
    .row {
      display: grid;
      grid-template-columns: 0.55rem 1fr auto;
      align-items: center;
      gap: 0.5rem;
      padding: 0.2rem 0.25rem;
    }
    .name { font-size: 0.85rem; }
    .state { font-size: 0.75rem; color: #6B6560; }
  `,
})
export class StatusPillComponent {
  private readiness = inject(ReadinessService);

  overall = this.readiness.overall;

  rows = computed(() => {
    const c = this.readiness.components();
    return (Object.keys(LABELS) as ComponentName[]).map((key) => ({
      key,
      label: LABELS[key],
      state: c[key] as ComponentState,
    }));
  });

  stateLabel(state: ComponentState): string {
    return STATE_LABELS[state];
  }
}

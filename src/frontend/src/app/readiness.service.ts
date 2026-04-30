import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

export type ComponentState = 'ready' | 'bootstrapping' | 'error';
export type ComponentName = 'adb' | 'oracleFree' | 'postgres' | 'mongo' | 'agentsTeam' | 'riskDashboard';

export interface ReadinessSnapshot {
  overall: ComponentState;
  components: Record<ComponentName, ComponentState>;
}

const POLL_MS = 5000;

@Injectable({ providedIn: 'root' })
export class ReadinessService {
  private http = inject(HttpClient);

  snapshot = signal<ReadinessSnapshot>({
    overall: 'bootstrapping',
    components: {
      adb: 'bootstrapping',
      oracleFree: 'bootstrapping',
      postgres: 'bootstrapping',
      mongo: 'bootstrapping',
      agentsTeam: 'bootstrapping',
      riskDashboard: 'bootstrapping',
    },
  });

  overall = computed(() => this.snapshot().overall);
  components = computed(() => this.snapshot().components);

  appReady = computed(() => this.allReady('oracleFree', 'postgres', 'mongo'));
  sidecarReady = computed(() => this.allReady('adb', 'oracleFree', 'postgres'));
  agentsReady = computed(() => this.allReady('adb', 'agentsTeam'));
  riskReady = computed(() => this.allReady('riskDashboard', 'oracleFree', 'postgres', 'mongo'));

  constructor() {
    this.poll();
    setInterval(() => this.poll(), POLL_MS);
  }

  private poll(): void {
    this.http.get<ReadinessSnapshot>('/api/v1/ready').subscribe({
      next: (r) => this.snapshot.set(r),
      error: () => this.snapshot.set({
        overall: 'error',
        components: {
          adb: 'error', oracleFree: 'error', postgres: 'error',
          mongo: 'error', agentsTeam: 'error', riskDashboard: 'error',
        },
      }),
    });
  }

  private allReady(...names: ComponentName[]): boolean {
    const c = this.components();
    return names.every((n) => c[n] === 'ready');
  }
}

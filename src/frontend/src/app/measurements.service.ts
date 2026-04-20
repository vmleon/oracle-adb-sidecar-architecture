import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin } from 'rxjs';
import { QueryService, Route, Table } from './query.service';

export interface Aggregate {
  queryId: string;
  route: Route;
  count: number;
  mean: number;
  median: number;
  p95: number;
  p99: number;
  stddev: number;
  min: number;
  max: number;
}

export interface SeriesPoint {
  measuredAt: string;
  elapsedMs: number;
  rowsReturned: number;
  success: number;
  runId: string;
}

const COMBOS: Array<{ table: Table; route: Route }> = [
  { table: 'accounts',        route: 'direct' },
  { table: 'accounts',        route: 'federated' },
  { table: 'transactions',    route: 'direct' },
  { table: 'transactions',    route: 'federated' },
  { table: 'policies',        route: 'direct' },
  { table: 'policies',        route: 'federated' },
  { table: 'rules',           route: 'direct' },
  { table: 'rules',           route: 'federated' },
  { table: 'support_tickets', route: 'direct' },
];

@Injectable({ providedIn: 'root' })
export class MeasurementsService {
  private http = inject(HttpClient);
  private query = inject(QueryService);

  aggregate(trim: 'none' | 'iqr'): Observable<Aggregate[]> {
    return this.http.get<Aggregate[]>('/api/v1/measurements', { params: { trim } });
  }

  series(queryId: string, route: Route, limit = 200): Observable<SeriesPoint[]> {
    return this.http.get<SeriesPoint[]>('/api/v1/measurements/series', {
      params: { queryId, route, limit },
    });
  }

  triggerRounds(n: number): Observable<unknown[]> {
    const requests = [] as Observable<unknown>[];
    for (let i = 0; i < n; i++) {
      const runId = crypto.randomUUID();
      for (const c of COMBOS) {
        requests.push(this.query.run(c.table, c.route, runId));
      }
    }
    return forkJoin(requests);
  }
}

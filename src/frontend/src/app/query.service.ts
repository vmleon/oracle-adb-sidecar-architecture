import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type Route = 'direct' | 'federated';
export type Table =
  | 'accounts'
  | 'transactions'
  | 'policies'
  | 'rules'
  | 'support_tickets';

export type Row = Record<string, unknown>;

export interface QueryResponse {
  rows: Row[];
  rowsReturned: number;
  elapsedMs: number;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class QueryService {
  private http = inject(HttpClient);

  run(table: Table, route: Route, runId: string): Observable<QueryResponse> {
    const params = { table, route, runId };
    return this.http.get<QueryResponse>('/api/v1/query', { params });
  }
}

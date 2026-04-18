import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export type Row = Record<string, unknown>;

export type DemoResponse = {
  oracle: {
    accounts?: Row[];
    transactions?: Row[];
    accounts_error?: string;
    transactions_error?: string;
  };
  postgres: {
    policies?: Row[];
    rules?: Row[];
    policies_error?: string;
    rules_error?: string;
  };
  mongo: {
    support_tickets?: Row[];
    error?: string;
  };
};

@Injectable({ providedIn: 'root' })
export class VersionsService {
  private http = inject(HttpClient);

  fetch() {
    return this.http.get<DemoResponse>('/api/v1/demo');
  }

  fetchViaSidecar() {
    return this.http.get<DemoResponse>('/api/v1/demo/via-sidecar');
  }
}

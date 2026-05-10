import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface CycleRow {
  aId: number;
  bId: number;
  cId: number;
  aName: string;
  bName: string;
  cName: string;
  amount: number;
  riskScore: number;
}

export interface FanoutRow {
  srcId: number;
  srcName: string;
  fanout: number;
  riskScore: number;
}

export interface StructuringRow {
  srcId: number;
  srcName: string;
  transferCount: number;
  totalAmount: number;
  riskScore: number;
}

export interface CrossBorderRow {
  country: string;
  txnCount: number;
  totalAmount: number;
  sanctioned: boolean;
}

export interface FraudPatterns {
  from: string;
  to: string;
  loadedAt: string;
  cycles: CycleRow[];
  fanout: FanoutRow[];
  structuring: StructuringRow[];
  crossBorderWires: CrossBorderRow[];
}

@Injectable({ providedIn: 'root' })
export class FraudService {
  private http = inject(HttpClient);

  load(from?: string, to?: string): Observable<FraudPatterns> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<FraudPatterns>('/api/v1/fraud/patterns', { params });
  }
}

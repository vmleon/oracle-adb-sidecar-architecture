import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface RiskKpis {
  kycAttention: number;
  frozenAccounts: number;
  highRiskCustomers: number;
  subCtrActivity: number;
  declineVelocity: number;
  openHighPriorityTickets: number;
}

export interface SubCtrRow {
  customer: string;
  wireCount: number;
  cashCount: number;
  otherCount: number;
  total: number;
}

export interface CrossBorderRow {
  country: string;
  txnCount: number;
  totalAmount: number;
}

export interface DeclineEvent {
  customer: string;
  accountId: number;
  amount: number;
  currency: string;
  merchant: string | null;
  country: string | null;
  occurredAt: string;
}

export interface KycPipeline {
  counts: { status: string; count: number }[];
  nonVerified: {
    id: number;
    name: string;
    country: string;
    kycStatus: string;
    riskTier: string;
    joinedAt: string;
  }[];
}

export interface RiskByStatusRow {
  riskTier: string;
  accountStatus: string;
  count: number;
}

export interface TicketBucket {
  date: string;
  priority: string;
  count: number;
}

export interface RuleRow {
  code: string;
  name: string;
  severity: string;
  description: string;
  policyCode: string;
  violationCount: number | null;
}

export interface RiskDashboard {
  kpis: RiskKpis;
  subCtrWatchlist: SubCtrRow[];
  crossBorderWires: CrossBorderRow[];
  declineEvents: DeclineEvent[];
  kycPipeline: KycPipeline;
  riskByStatus: RiskByStatusRow[];
  ticketsByPriority: TicketBucket[];
  rules: RuleRow[];
}

@Injectable({ providedIn: 'root' })
export class RiskService {
  private http = inject(HttpClient);

  load(): Observable<RiskDashboard> {
    return this.http.get<RiskDashboard>('/api/v1/risk');
  }
}

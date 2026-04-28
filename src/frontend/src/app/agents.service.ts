import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AgentRunRequest {
  prompt: string;
  conversationId?: string;
}

export interface AgentTaskTrace {
  agentName: string;
  taskName: string;
  taskOrder: number;
  input: string;
  result: string;
  state: string;
  durationMillis: number;
}

export interface AgentToolTrace {
  agentName: string;
  toolName: string;
  taskName: string;
  taskOrder: number;
  input: string;
  output: string;
  toolOutput: string;
  durationMillis: number;
}

export interface AgentTrace {
  teamExecId: string;
  teamName: string;
  state: string;
  tasks: AgentTaskTrace[];
  tools: AgentToolTrace[];
}

export interface AgentRunResponse {
  prompt: string;
  answer: string;
  conversationId: string;
  elapsedMillis: number;
  trace: AgentTrace | null;
}

@Injectable({ providedIn: 'root' })
export class AgentsService {
  private http = inject(HttpClient);

  run(req: AgentRunRequest): Observable<AgentRunResponse> {
    return this.http.post<AgentRunResponse>('/api/v1/agents', req);
  }
}

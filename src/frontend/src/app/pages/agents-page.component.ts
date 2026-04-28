import { Component, signal } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { AgentsService, AgentRunResponse } from "../agents.service";

interface ChatTurn {
  role: "user" | "assistant" | "error";
  text: string;
  trace?: AgentRunResponse["trace"];
  elapsedMillis?: number;
  showTrace?: boolean;
}

const CHIPS: string[] = [
  "Are there any suspicious patterns on Carol Diaz's accounts this month?",
  "Bob Chen disputed a $230 charge — what should we do?",
  "Summarise Alice Morgan's risk profile.",
  "Why is Jamal Reed's checking account frozen?",
  "What policies apply to international wires above $10K?",
];

@Component({
  selector: "app-agents-page",
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <h2>Select AI Agents</h2>
    <p class="subtitle">
      A four-agent banking investigation team running entirely inside ADB. One
      prompt fans out to a Transaction Analyst, a Compliance Officer (SQL +
      RAG), a Customer Care Liaison, and a Case Synthesiser.
    </p>
    <div class="chips">
      <button *ngFor="let c of chips" (click)="setPrompt(c)">{{ c }}</button>
    </div>
    <div class="conversation">
      <div *ngFor="let turn of turns()" [class]="'bubble ' + turn.role">
        <div class="text">{{ turn.text }}</div>
        <div
          *ngIf="turn.role === 'assistant' && turn.elapsedMillis"
          class="badge"
        >
          {{ turn.elapsedMillis }} ms
        </div>
        <button
          *ngIf="turn.trace"
          (click)="turn.showTrace = !turn.showTrace"
          class="trace-toggle"
        >
          {{ turn.showTrace ? "Hide" : "Show" }} execution trace ({{
            turn.trace.tasks.length
          }}
          tasks, {{ turn.trace.tools.length }} tool calls)
        </button>
        <div *ngIf="turn.showTrace && turn.trace" class="trace">
          <div *ngFor="let t of turn.trace.tasks" class="task">
            <div class="task-header">
              Task #{{ t.taskOrder }} {{ t.agentName }} ·
              {{ t.durationMillis }} ms · {{ t.state }}
            </div>
            <details>
              <summary>Input</summary>
              <pre>{{ t.input }}</pre>
            </details>
            <ng-container
              *ngFor="let tool of toolsFor(turn.trace, t.taskOrder)"
            >
              <details>
                <summary>
                  Tool: {{ tool.toolName }} ({{ tool.durationMillis }} ms)
                </summary>
                <pre>{{ tool.input }}</pre>
                <pre>{{ tool.output }}</pre>
              </details>
            </ng-container>
            <details>
              <summary>Result</summary>
              <pre>{{ t.result }}</pre>
            </details>
          </div>
        </div>
      </div>
      <div *ngIf="loading()" class="bubble assistant"><em>Thinking…</em></div>
    </div>
    <div class="composer">
      <textarea
        [(ngModel)]="promptModel"
        rows="3"
        placeholder="Ask the team..."
      ></textarea>
      <button (click)="send()" [disabled]="loading() || !promptModel.trim()">
        Send →
      </button>
      <button (click)="newConversation()" class="secondary">
        New conversation
      </button>
    </div>
  `,
  styles: [
    `
      .subtitle {
        color: #555;
        margin-bottom: 16px;
      }
      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-bottom: 16px;
      }
      .chips button {
        padding: 6px 12px;
        border-radius: 16px;
        border: 1px solid #ccc;
        background: #f7f7f9;
        cursor: pointer;
      }
      .chips button:hover {
        background: #eef;
      }
      .conversation {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-bottom: 16px;
      }
      .bubble {
        padding: 12px 16px;
        border-radius: 8px;
        max-width: 80%;
      }
      .bubble.user {
        background: #e7f0ff;
        align-self: flex-end;
      }
      .bubble.assistant {
        background: #f4f4f6;
        align-self: flex-start;
      }
      .bubble.error {
        background: #fde7e7;
        align-self: flex-start;
      }
      .text {
        white-space: pre-wrap;
      }
      .badge {
        display: inline-block;
        margin-top: 6px;
        padding: 2px 8px;
        background: #ddd;
        border-radius: 4px;
        font-size: 0.85em;
      }
      .trace-toggle {
        margin-top: 8px;
        background: none;
        border: 1px dashed #888;
        padding: 4px 10px;
        cursor: pointer;
      }
      .trace {
        margin-top: 8px;
        border-top: 1px solid #ccc;
        padding-top: 8px;
      }
      .task {
        margin: 8px 0;
      }
      .task-header {
        font-weight: 600;
      }
      pre {
        background: #fafafa;
        padding: 8px;
        overflow-x: auto;
        max-height: 200px;
      }
      .composer {
        display: flex;
        gap: 8px;
        align-items: stretch;
      }
      .composer textarea {
        flex: 1;
        padding: 8px;
      }
      .composer button {
        padding: 8px 16px;
      }
      .composer button.secondary {
        background: none;
        color: #666;
        border: 1px solid #ccc;
      }
    `,
  ],
})
export class AgentsPageComponent {
  chips = CHIPS;
  promptModel = "";
  conversationId = signal<string | undefined>(undefined);
  turns = signal<ChatTurn[]>([]);
  loading = signal(false);

  constructor(private agents: AgentsService) {}

  setPrompt(text: string) {
    this.promptModel = text;
  }

  send() {
    const text = this.promptModel.trim();
    if (!text) return;
    this.turns.update((t) => [...t, { role: "user", text }]);
    this.promptModel = "";
    this.loading.set(true);

    this.agents
      .run({ prompt: text, conversationId: this.conversationId() })
      .subscribe({
        next: (resp) => {
          this.conversationId.set(resp.conversationId);
          this.turns.update((t) => [
            ...t,
            {
              role: "assistant",
              text: resp.answer,
              trace: resp.trace,
              elapsedMillis: resp.elapsedMillis,
              showTrace: false,
            },
          ]);
          this.loading.set(false);
        },
        error: (err) => {
          const msg = err?.error?.error ?? err?.message ?? "Agent run failed";
          this.turns.update((t) => [...t, { role: "error", text: msg }]);
          this.loading.set(false);
        },
      });
  }

  newConversation() {
    this.conversationId.set(undefined);
    this.turns.set([]);
  }

  toolsFor(trace: AgentRunResponse["trace"], taskOrder: number) {
    return trace ? trace.tools.filter((t) => t.taskOrder === taskOrder) : [];
  }
}

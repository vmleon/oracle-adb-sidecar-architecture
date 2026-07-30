import { Component, computed, inject, signal } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { DomSanitizer, SafeHtml } from "@angular/platform-browser";
import { AgentsService, AgentRunResponse } from "../agents.service";
import { ReadinessService } from "../readiness.service";

interface ChatTurn {
  role: "user" | "assistant" | "error";
  text: string;
  trace?: AgentRunResponse["trace"];
  elapsedMillis?: number;
  showTrace?: boolean;
}

// A tool call's JSON request/response, reduced to what is worth reading.
interface ToolRequest {
  query?: string;
  action?: string;
  raw?: string;
}

interface ToolResponse {
  status?: string;
  body: string;
}

const CHIPS: string[] = [
  "Are there any suspicious patterns on Carol Diaz's accounts this month?",
  "Bob Chen disputed a $230 charge — what should we do?",
  "Summarise Alice Morgan's risk profile.",
  "Why is Jamal Reed's checking account frozen?",
  "What policies apply to international wires above $10K?",
];

// Per-agent human-team time estimates, in minutes of focused work. The
// numbers are deliberately broad — the goal is order-of-magnitude
// comparison ("seconds for the AI vs hours for a team"), not precise
// staffing. Edit these if you have a better local benchmark.
interface HumanTaskMeta {
  label: string;
  description: string;
  loMin: number;
  hiMin: number;
}
const HUMAN_TASK_META: Record<string, HumanTaskMeta> = {
  TRANSACTION_ANALYST: {
    label: "Transaction Analyst",
    description:
      "pull customer / account / transaction history from the warehouse and spot patterns",
    loMin: 30,
    hiMin: 60,
  },
  COMPLIANCE_OFFICER: {
    label: "Compliance Officer",
    description:
      "run rule queries and read the relevant policy documents to cite the right clauses",
    loMin: 60,
    hiMin: 120,
  },
  CUSTOMER_CARE_LIAISON: {
    label: "Customer Care Liaison",
    description:
      "pull customer history, support tickets, and prior interactions",
    loMin: 15,
    hiMin: 30,
  },
  CASE_SYNTHESIZER: {
    label: "Case Synthesiser",
    description: "read all specialist reports and draft the final case file",
    loMin: 30,
    hiMin: 60,
  },
};
const COORDINATION_LO_MIN = 30;
const COORDINATION_HI_MIN = 60;

@Component({
  selector: "app-agents-page",
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <h2>Banking Assistant <span class="suite">(Select AI)</span></h2>
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
        <div class="text" [innerHTML]="renderMarkdown(turn.text)"></div>
        <div *ngIf="turn.role === 'assistant' && turn.elapsedMillis" class="timing">
          <span class="badge ai">AI: {{ formatDuration(turn.elapsedMillis) }}</span>
          <div class="badge human pill" tabindex="0">
            <span>Human team: ~{{ humanRange }} (rough estimate — how long would it take yours?)</span>
            <div class="popup">
              <h4>Estimated effort for a small banking team</h4>
              <ul>
                <li *ngFor="let row of humanBreakdown">
                  <strong>{{ row.label }}</strong>
                  <span class="time">~{{ row.loMin }}–{{ row.hiMin }} min</span>
                  <div class="desc">{{ row.description }}</div>
                </li>
                <li>
                  <strong>Coordination &amp; meetings</strong>
                  <span class="time">~{{ coordinationLo }}–{{ coordinationHi }} min</span>
                  <div class="desc">handoffs between specialists</div>
                </li>
              </ul>
              <p class="disclaimer">
                Order-of-magnitude estimate of focused work. Real wall-clock
                time depends on staffing, prioritisation, and ticket queue
                depth and is usually longer.
              </p>
            </div>
          </div>
        </div>
        <button
          *ngIf="turn.trace"
          (click)="turn.showTrace = !turn.showTrace"
          class="trace-toggle"
        >
          <span class="caret" [class.open]="turn.showTrace">&#9656;</span>
          {{ turn.showTrace ? "Hide" : "Show" }} execution trace
          <span class="trace-count">
            {{ turn.trace.tasks.length }} tasks ·
            {{ turn.trace.tools.length }} tool calls
          </span>
        </button>
        <div *ngIf="turn.showTrace && turn.trace" class="trace">
          <div *ngFor="let t of turn.trace.tasks" class="task">
            <div class="task-header">
              <span class="step">{{ t.taskOrder + 1 }}</span>
              <span class="agent">{{ t.agentName }}</span>
              <span
                class="state"
                [class.ok]="t.state === 'SUCCEEDED'"
                [class.bad]="t.state !== 'SUCCEEDED'"
                >{{ t.state }}</span
              >
              <span class="dur">{{ formatDuration(t.durationMillis) }}</span>
            </div>
            <details class="block">
              <summary>Input</summary>
              <pre class="plain">{{ t.input }}</pre>
            </details>
            <details
              class="block"
              *ngFor="let tool of toolsFor(turn.trace, t.taskOrder)"
            >
              <summary>
                <span class="tool-name">{{ tool.toolName }}</span>
                <span class="dur">{{ formatDuration(tool.durationMillis) }}</span>
              </summary>
              <ng-container *ngIf="toolRequest(tool.input) as req">
                <div class="field" *ngIf="req.query">
                  <span class="label">Query</span>
                  <pre class="plain">{{ req.query }}</pre>
                </div>
                <div class="field" *ngIf="req.action">
                  <span class="label">Action</span>
                  {{ req.action }}
                </div>
                <div class="field" *ngIf="req.raw">
                  <span class="label">Request</span>
                  <pre class="plain scroll">{{ req.raw }}</pre>
                </div>
              </ng-container>
              <div class="field" *ngIf="toolResponse(tool.output) as res">
                <span class="label">
                  Response
                  <span class="status" *ngIf="res.status">· {{ res.status }}</span>
                </span>
                <pre class="plain scroll">{{ res.body }}</pre>
              </div>
            </details>
            <details class="block">
              <summary>Result</summary>
              <div class="text result" [innerHTML]="renderMarkdown(t.result)"></div>
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
      <button (click)="send()" [disabled]="loading() || !ready() || !promptModel.trim()">
        {{ ready() ? 'Research →' : 'Waiting for agents…' }}
      </button>
      <button (click)="newConversation()" class="secondary" [disabled]="loading()">
        New conversation
      </button>
    </div>
  `,
  styles: [
    `
      .suite {
        font-weight: normal;
        color: #6B6560;
        font-size: 0.7em;
      }
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
        padding: 6px 14px;
        border-radius: 16px;
        border: 1px solid #C74634;
        background: #F5F2EE;
        color: #C74634;
        font-size: 0.85rem;
        cursor: pointer;
        transition: background 0.15s, color 0.15s;
      }
      .chips button:hover {
        background: #C74634;
        color: #FFFFFF;
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
        line-height: 1.45;
      }
      .text p { margin: 0 0 0.5em; }
      .text p:last-child { margin-bottom: 0; }
      .text strong { font-weight: 600; }
      .text em { font-style: italic; }
      .text ul { margin: 0.25em 0 0.5em 1.25em; padding: 0; }
      .text li { margin: 0.15em 0; }
      .text code {
        background: #eef0f3;
        padding: 0.05em 0.3em;
        border-radius: 3px;
        font-size: 0.9em;
      }
      .timing {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin-top: 6px;
      }
      .badge {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 4px;
        font-size: 0.85em;
      }
      .badge.ai {
        background: #1A7F3C;
        color: #FFFFFF;
      }
      .badge.human {
        background: #E8DCC9;
        color: #2C2723;
      }
      .badge.pill {
        position: relative;
        cursor: default;
      }
      .badge.pill .popup {
        position: absolute;
        top: 0;
        left: calc(100% + 0.4rem);
        min-width: 22rem;
        max-width: 28rem;
        background: #FFFFFF;
        color: #2C2723;
        border: 1px solid #E5E0DA;
        border-radius: 6px;
        padding: 0.75rem 0.9rem;
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
        display: none;
        z-index: 20;
        font-size: 0.85em;
        line-height: 1.45;
      }
      .badge.pill:hover .popup,
      .badge.pill:focus-within .popup {
        display: block;
      }
      .badge.pill .popup h4 {
        margin: 0 0 0.5em;
        font-family: Georgia, serif;
        font-size: 0.95rem;
        color: #2C2723;
      }
      .badge.pill .popup ul {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .badge.pill .popup li {
        padding: 0.4em 0;
        border-top: 1px solid #F0EBE3;
      }
      .badge.pill .popup li:first-child {
        border-top: none;
      }
      .badge.pill .popup .time {
        float: right;
        color: #4A453F;
        font-variant-numeric: tabular-nums;
      }
      .badge.pill .popup .desc {
        color: #6B6560;
        font-size: 0.85em;
        margin-top: 0.15em;
      }
      .badge.pill .popup .disclaimer {
        margin: 0.6em 0 0;
        color: #6B6560;
        font-style: italic;
        font-size: 0.85em;
      }
      /* The global button rule paints white-on-red. A ghost button has to
         restate the colour or it inherits white text onto the bubble. */
      .trace-toggle {
        margin-top: 10px;
        display: inline-flex;
        align-items: center;
        gap: 8px;
        background: #ffffff;
        color: #c74634;
        border: 1px solid #e5e0da;
        border-radius: 6px;
        padding: 6px 12px;
        font-size: 0.85rem;
        font-weight: 600;
      }
      /* Beats button:hover:not(:disabled) on specificity, so the label stays
         legible instead of white-on-white at rest and red-on-red on hover. */
      .trace-toggle:hover:not(:disabled) {
        background: #c74634;
        color: #ffffff;
        border-color: #c74634;
      }
      .trace-toggle .caret {
        display: inline-block;
        transition: transform 0.15s;
      }
      .trace-toggle .caret.open {
        transform: rotate(90deg);
      }
      .trace-toggle .trace-count {
        opacity: 0.7;
        font-weight: 400;
      }
      .trace {
        margin-top: 12px;
        border-top: 1px solid #e5e0da;
        padding-top: 12px;
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .task {
        background: #ffffff;
        border: 1px solid #e5e0da;
        border-radius: 8px;
        padding: 10px 12px;
      }
      .task-header {
        display: flex;
        align-items: center;
        gap: 8px;
        flex-wrap: wrap;
        margin-bottom: 6px;
      }
      .task-header .step {
        width: 20px;
        height: 20px;
        flex: none;
        border-radius: 50%;
        background: #c74634;
        color: #ffffff;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 0.72rem;
        font-weight: 700;
      }
      .task-header .agent {
        font-weight: 600;
        font-size: 0.9rem;
      }
      .task-header .state {
        font-size: 0.68rem;
        font-weight: 700;
        letter-spacing: 0.04em;
        padding: 2px 6px;
        border-radius: 4px;
      }
      .task-header .state.ok {
        background: #e4f1e8;
        color: #1a7f3c;
      }
      .task-header .state.bad {
        background: #fde7e7;
        color: #b3261e;
      }
      .dur {
        margin-left: auto;
        color: #6b6560;
        font-size: 0.8rem;
        font-variant-numeric: tabular-nums;
      }
      .block {
        border-top: 1px solid #f0ede8;
      }
      .block:first-of-type {
        border-top: none;
      }
      .block > summary {
        cursor: pointer;
        padding: 6px 0;
        font-size: 0.82rem;
        font-weight: 600;
        display: flex;
        align-items: center;
        gap: 8px;
        list-style: none;
      }
      .block > summary::-webkit-details-marker {
        display: none;
      }
      .block > summary::before {
        content: "\\25B8";
        color: #6b6560;
        display: inline-block;
        transition: transform 0.15s;
      }
      .block[open] > summary::before {
        transform: rotate(90deg);
      }
      .tool-name {
        font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      }
      .field {
        margin: 2px 0 8px;
      }
      .field .label {
        display: block;
        font-size: 0.68rem;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: #6b6560;
        margin-bottom: 3px;
      }
      .field .status {
        text-transform: none;
        letter-spacing: 0;
      }
      /* pre-wrap so long SQL and JSON reflow instead of forcing a
         horizontal scrollbar inside every block. */
      .task pre.plain {
        background: #faf8f5;
        padding: 8px 10px;
        margin: 0;
        font-size: 0.78rem;
        white-space: pre-wrap;
        word-break: break-word;
      }
      .task pre.scroll {
        max-height: 260px;
        overflow-y: auto;
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
  private readiness = inject(ReadinessService);
  private sanitizer = inject(DomSanitizer);
  chips = CHIPS;
  promptModel = "";
  conversationId = signal<string | undefined>(undefined);
  turns = signal<ChatTurn[]>([]);
  loading = signal(false);
  ready = this.readiness.agentsReady;

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

  // The SQL/RAG tools exchange JSON, and their `result` is itself a JSON
  // string — so a raw dump shows two levels of escaping and reads as one
  // unbroken line of \n and \". These two helpers unwrap that into the
  // fields worth seeing. Both are called from the template, so results are
  // memoised on the raw string: change detection would otherwise re-parse
  // every payload on every tick.
  private requestCache = new Map<string, ToolRequest>();
  private responseCache = new Map<string, ToolResponse>();

  toolRequest(input: string | null | undefined): ToolRequest {
    if (!input) return {};
    const hit = this.requestCache.get(input);
    if (hit) return hit;

    let parsed: ToolRequest;
    try {
      const o = JSON.parse(input);
      const query = o.QUERY ?? o.query;
      const action = o.ACTION ?? o.action;
      // Only treat it as structured if we recognised something; otherwise
      // show the original rather than silently dropping fields.
      parsed = query || action ? { query, action } : { raw: input };
    } catch {
      parsed = { raw: input };
    }
    this.requestCache.set(input, parsed);
    return parsed;
  }

  toolResponse(output: string | null | undefined): ToolResponse {
    if (!output) return { body: "" };
    const hit = this.responseCache.get(output);
    if (hit) return hit;

    let parsed: ToolResponse;
    try {
      const o = JSON.parse(output);
      const inner = o.result ?? o.RESULT;
      let body: string;
      if (typeof inner === "string") {
        // Usually a JSON array of rows; sometimes prose (e.g. NL2SQL
        // declining a prompt). Pretty-print the former, and let the latter
        // keep its real newlines instead of literal \n.
        try {
          body = JSON.stringify(JSON.parse(inner), null, 2);
        } catch {
          body = inner;
        }
      } else if (inner !== undefined) {
        body = JSON.stringify(inner, null, 2);
      } else {
        body = JSON.stringify(o, null, 2);
      }
      parsed = { status: o.status ?? o.STATUS, body };
    } catch {
      parsed = { body: output };
    }
    this.responseCache.set(output, parsed);
    return parsed;
  }

  // Show sub-second values in ms (tool calls can be < 1 s); anything
  // longer in seconds with one decimal. Agent runs are tens of seconds,
  // so the seconds form is the meaningful one to compare with the
  // human-team estimate.
  formatDuration(ms: number | null | undefined): string {
    if (ms == null) return "";
    if (ms < 1000) return `${ms} ms`;
    return `${(ms / 1000).toFixed(1)} s`;
  }

  coordinationLo = COORDINATION_LO_MIN;
  coordinationHi = COORDINATION_HI_MIN;

  // The BANKING_INVESTIGATION_TEAM always runs the same four agents in
  // sequence (see README §Select AI Agents). Estimates are a property of
  // the team, not of a specific run, so we precompute once at class init
  // instead of recomputing on every change-detection tick.
  humanBreakdown: HumanTaskMeta[] = Object.values(HUMAN_TASK_META);

  humanRange: string = (() => {
    const totals = Object.values(HUMAN_TASK_META).reduce(
      (acc, meta) => ({ lo: acc.lo + meta.loMin, hi: acc.hi + meta.hiMin }),
      { lo: COORDINATION_LO_MIN, hi: COORDINATION_HI_MIN },
    );
    return totals.hi >= 90
      ? `${(totals.lo / 60).toFixed(1)}–${(totals.hi / 60).toFixed(1)} hours`
      : `${totals.lo}–${totals.hi} min`;
  })();

  // Minimal Markdown subset that the agents actually emit: bold, italics,
  // bullet lists, inline code, and paragraph breaks. HTML-escape first so
  // an LLM cannot inject markup. Anything more elaborate (tables, fenced
  // code blocks, headings) would justify pulling in a real renderer.
  renderMarkdown(text: string): SafeHtml {
    if (!text) return '';
    let html = text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    html = html.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    html = html.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>');
    const paragraphs = html.split(/\n{2,}/).map((block) => {
      const lines = block.split('\n');
      const isList = lines.every((l) => /^\s*[-*]\s+/.test(l));
      if (isList) {
        const items = lines.map((l) => l.replace(/^\s*[-*]\s+/, ''));
        return '<ul>' + items.map((i) => `<li>${i}</li>`).join('') + '</ul>';
      }
      return `<p>${block.replace(/\n/g, '<br>')}</p>`;
    });
    return this.sanitizer.bypassSecurityTrustHtml(paragraphs.join(''));
  }
}

import { Component } from '@angular/core';

@Component({
  selector: 'app-future-page',
  template: `
    <h2>AI features</h2>
    <p class="subtitle">
      With ADB 26ai in place as a sidecar, you can layer modern AI features
      over your existing data without migrating the production databases.
      Vector Search, Hybrid Vector Index, and Select AI Agents all run inside
      the sidecar and reach into the same V_* views you saw on the sidecar
      page.
    </p>
    <div class="placeholder">
      <h3>Select AI Agents feature goes here</h3>
      <p>Planned for a future iteration.</p>
    </div>
  `,
  styles: `
    h2 { font-family: Georgia, serif; margin-bottom: 0.25rem; color: #2C2723; }
    .subtitle { color: #6B6560; margin-bottom: 1.25rem; font-size: 0.9rem; line-height: 1.4; }
    .placeholder {
      background: #FFFFFF;
      border: 1px dashed #C74634;
      border-radius: 8px;
      padding: 2rem;
      text-align: center;
      color: #2C2723;
    }
    .placeholder h3 { margin: 0 0 0.5rem; color: #C74634; }
  `,
})
export class FuturePageComponent {}

import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { NavComponent } from './nav/nav.component';
import { StatusPillComponent } from './status-pill/status-pill.component';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet, NavComponent, StatusPillComponent],
  template: `
    <header>
      <h1>
        <a routerLink="/risk">
          <span class="oracle-red">Oracle</span> ADB Sidecar Architecture
        </a>
      </h1>
      <app-status-pill />
    </header>
    <main>
      <app-nav />
      <router-outlet />
    </main>
    <footer>
      Created by <a href="https://www.linkedin.com/in/victormartindeveloper/" target="_blank" rel="noopener">Victor Martin</a> at Oracle Database EMEA Platform Technology Solutions (2026)
    </footer>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 100vh; }
    header {
      padding: 0.75rem 1.5rem;
      background: #2C2723;
      border-bottom: 1px solid #3C3835;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
    }
    h1 { margin: 0; font-size: 1.1rem; color: #F1EFED; }
    h1 a { color: inherit; text-decoration: none; }
    h1 a:hover { text-decoration: underline; }
    h1 .oracle-red { color: #C74634; }
    main {
      flex: 1;
      padding: 2rem 1.5rem;
      max-width: 1100px;
      width: 100%;
      margin: 0 auto;
    }
    footer {
      padding: 0.75rem 1.5rem;
      background: #2C2723;
      border-top: 1px solid #3C3835;
      text-align: center;
      color: #9B9590;
      font-size: 0.8rem;
    }
    footer a { color: #E88A7A; text-decoration: none; }
    footer a:hover { text-decoration: underline; }
  `,
})
export class App {}

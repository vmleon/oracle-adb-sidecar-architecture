import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="nav">
      <section class="group-wrap">
        <span class="group-label">Current Application</span>
        <div class="group">
          <a routerLink="/risk" routerLinkActive="active">Risk Dashboard</a>
          <a routerLink="/app" routerLinkActive="active">Current System</a>
        </div>
      </section>
      <section class="group-wrap">
        <span class="group-label">AI-powered App Extension</span>
        <div class="group">
          <a routerLink="/ai-data-gateway" routerLinkActive="active">AI Data Gateway</a>
          <a routerLink="/fraud" routerLinkActive="active">Fraud Dashboard</a>
          <a routerLink="/agents" routerLinkActive="active">AI Assistant</a>
          <a routerLink="/measurements" routerLinkActive="active">Measurements</a>
        </div>
      </section>
    </nav>
  `,
  styles: `
    .nav {
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      padding: 0.75rem 0;
      border-bottom: 1px solid #E5E0DA;
      margin-bottom: 1.5rem;
    }
    .group-wrap {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .group-label {
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: #6B6560;
      text-align: center;
    }
    .group {
      display: flex;
      gap: 0.5rem;
      padding: 0.35rem 0.5rem;
      background: #F5F2EE;
      border-radius: 6px;
      box-shadow: 0 1px 2px rgba(44, 39, 35, 0.06);
    }
    .nav a {
      color: #2C2723;
      text-decoration: none;
      padding: 0.35rem 0.6rem;
      border-radius: 4px;
      font-size: 0.95rem;
    }
    .nav a.active {
      background: #C74634;
      color: #FFFFFF;
    }
  `,
})
export class NavComponent {}

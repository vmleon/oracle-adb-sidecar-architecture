import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="nav">
      <div class="left">
        <a routerLink="/app" routerLinkActive="active">Current app</a>
      </div>
      <div class="right">
        <a routerLink="/sidecar" routerLinkActive="active">ADB sidecar</a>
        <a routerLink="/agents" routerLinkActive="active">AI Assistant</a>
        <a routerLink="/measurements" routerLinkActive="active">Measurements</a>
      </div>
    </nav>
  `,
  styles: `
    .nav {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.75rem 0;
      border-bottom: 1px solid #E5E0DA;
      margin-bottom: 1.5rem;
    }
    .left, .right {
      display: flex;
      gap: 0.75rem;
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

import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';

import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';

interface NavItem {
  path: string;
  label: string;
  /** An SVG path drawn on a 24x24 grid. */
  icon: string;
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.css',
})
export class SidebarComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly themeService = inject(ThemeService);

  protected readonly navItems: NavItem[] = [
    { path: '/dashboard', label: 'Overview', icon: 'M3 13h4v8H3zM10 3h4v18h-4zM17 9h4v12h-4z' },
    { path: '/transactions', label: 'Ledger', icon: 'M4 5h16M4 12h16M4 19h10' },
    { path: '/budgets', label: 'Budgets', icon: 'M12 3v18M3 12h18M7.5 7.5l9 9M16.5 7.5l-9 9' },
    { path: '/categories', label: 'Categories', icon: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z' },
    { path: '/recurring', label: 'Recurring', icon: 'M3 12a9 9 0 0 1 15-6.7L21 8M21 12a9 9 0 0 1-15 6.7L3 16M21 4v4h-4M3 20v-4h4' },
  ];

  protected get username(): string {
    return this.auth.getUsername() ?? 'Account';
  }

  protected get initial(): string {
    return this.username.charAt(0).toUpperCase();
  }

  protected signOut(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}

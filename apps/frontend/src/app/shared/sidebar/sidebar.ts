import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';

import { AccountService } from '../../core/services/account.service';
import { AuthService } from '../../core/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';

interface NavItem {
  path: string;
  label: string;
  /** An SVG path drawn on a 24x24 grid. */
  icon: string;
  /**
   * Dropped from the bottom bar on small screens.
   *
   * Ten controls do not fit across a phone. Activity is the one that can go,
   * because every account's detail page links to it.
   */
  hideOnMobile?: boolean;
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
  private readonly accountService = inject(AccountService);
  private readonly router = inject(Router);
  protected readonly themeService = inject(ThemeService);

  protected readonly navItems: NavItem[] = [
    { path: '/dashboard', label: 'Overview', icon: 'M3 13h4v8H3zM10 3h4v18h-4zM17 9h4v12h-4z' },
    { path: '/transactions', label: 'Ledger', icon: 'M4 5h16M4 12h16M4 19h10' },
    { path: '/budgets', label: 'Budgets', icon: 'M12 3v18M3 12h18M7.5 7.5l9 9M16.5 7.5l-9 9' },
    { path: '/categories', label: 'Categories', icon: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z' },
    { path: '/recurring', label: 'Recurring', icon: 'M3 12a9 9 0 0 1 15-6.7L21 8M21 12a9 9 0 0 1-15 6.7L3 16M21 4v4h-4M3 20v-4h4' },
    { path: '/category-rules', label: 'Filing rules', icon: 'M4 6h16M4 12h10M4 18h6M16 15l3 3 4-5', hideOnMobile: true },
  ];

  protected readonly adminItems: NavItem[] = [
    { path: '/admin/users', label: 'Users', icon: 'M16 20v-1.5a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4V20M9.5 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M17 11l2 2 4-4' },
    { path: '/admin/activity', label: 'Activity', icon: 'M3 12h4l2.5-7 4 14 2.5-7h5', hideOnMobile: true },
  ];

  private readonly tokenRole = toSignal(this.auth.role$, { initialValue: this.auth.getRole() });

  /**
   * Whether to offer the admin section.
   *
   * The loaded profile wins when there is one, because it is the current answer;
   * the token's claim stands in until then, so the section does not appear a
   * moment late on every page load. Neither decides anything — the API rechecks
   * the stored role on every request — so being briefly wrong costs a 403, not
   * access.
   */
  protected readonly isAdmin = computed(() => {
    const profile = this.accountService.profile();
    return profile ? profile.role === 'ADMIN' : this.tokenRole() === 'ADMIN';
  });

  constructor() {
    // Asked for once per session. It also settles the case the token cannot: an
    // administrator who was demoted while this tab was open.
    this.accountService.load().subscribe({
      error: () => {
        // The token's claim carries the navigation until the next load. A failure
        // here is already reported by the interceptor if it ended the session.
      },
    });
  }

  protected get username(): string {
    return this.accountService.profile()?.username ?? this.auth.getUsername() ?? 'Account';
  }

  protected get initial(): string {
    return this.username.charAt(0).toUpperCase();
  }

  protected signOut(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}

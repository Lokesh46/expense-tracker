import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

interface NavLink {
  label: string;
  path: string;
}

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  templateUrl: './header.html',
  styleUrl: './header.css',
})
export class Header {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected readonly navLinks = signal<NavLink[]>([
    { label: 'Home', path: '/home' },
    { label: 'Dashboard', path: '/dashboard' },
    { label: 'Categories', path: '/categories' },
    { label: 'Transactions', path: '/transactions' },
    { label: 'Profile', path: '/profile' },
  ]);

  protected readonly isAuthenticated = signal(this.auth.getToken() != null);
  protected readonly isMenuOpen = signal(false);

  constructor() {
    this.auth.isAuthenticated$.subscribe((v) => this.isAuthenticated.set(v));
  }

  toggleMenu(): void {
    this.isMenuOpen.update((value) => !value);
  }

  closeMenu(): void {
    this.isMenuOpen.set(false);
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }

  onLogoutClick(event: Event): void {
    event.preventDefault();
    const confirmed = confirm('Are you sure you want to log out?');
    if (confirmed) {
      this.logout();
    }
  }
}


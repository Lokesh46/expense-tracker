import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-footer',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './footer.html',
  styleUrl: './footer.css',
})
export class Footer {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected readonly currentYear = computed(() => new Date().getFullYear());
  protected readonly isAuthenticated = signal(this.auth.getToken() != null);

  constructor() {
    this.auth.isAuthenticated$.subscribe((v) => this.isAuthenticated.set(v));
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

import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterOutlet } from '@angular/router';

import { AuthService } from './core/services/auth.service';
import { NotificationsComponent } from './shared/notifications/notifications';
import { SidebarComponent } from './shared/sidebar/sidebar';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, NotificationsComponent, SidebarComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /**
   * The sign-in and register screens are full-bleed and have no navigation, so
   * the shell only wraps the app once there is a session.
   */
  protected readonly isAuthenticated = toSignal(this.auth.isAuthenticated$, {
    initialValue: false,
  });

  protected skipToContent(event: Event): void {
    event.preventDefault();
    document.getElementById('main-content')?.focus();
  }
}

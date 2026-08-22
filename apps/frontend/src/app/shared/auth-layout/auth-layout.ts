import { Component, input } from '@angular/core';

import { ThemeService } from '../../core/services/theme.service';
import { inject } from '@angular/core';

/**
 * The split frame shared by sign-in and register: an editorial panel on one
 * side, the form on the other. Keeping it in one place stops the two screens
 * drifting apart.
 */
@Component({
  selector: 'app-auth-layout',
  standalone: true,
  templateUrl: './auth-layout.html',
  styleUrl: './auth-layout.css',
})
export class AuthLayoutComponent {
  readonly eyebrow = input.required<string>();
  readonly heading = input.required<string>();
  readonly blurb = input.required<string>();

  protected readonly themeService = inject(ThemeService);
}

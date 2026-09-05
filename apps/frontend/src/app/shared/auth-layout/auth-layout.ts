import { Component, inject, input } from '@angular/core';

import { ThemeService } from '../../core/services/theme.service';
import { WakeService } from '../../core/services/wake.service';

/**
 * The split frame shared by sign-in and register: an editorial panel on one
 * side, the form on the other. Keeping it in one place stops the two screens
 * drifting apart.
 *
 * <p>It is also where the API gets woken. Both screens it wraps are entry
 * points a visitor reaches with the instance possibly asleep, and both end in a
 * request that would otherwise absorb the whole cold start with no explanation.
 * Starting the wake here spends the boot while the form is being filled in.
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

  private readonly wakeService = inject(WakeService);

  protected readonly wakeStatus = this.wakeService.status;
  protected readonly waitedSeconds = this.wakeService.waitedSeconds;

  constructor() {
    this.wakeService.wake();
  }
}

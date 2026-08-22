import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { NotificationService } from '../../core/services/notification.service';

@Component({
  selector: 'app-notifications',
  standalone: true,
  templateUrl: './notifications.html',
  styleUrl: './notifications.css',
})
export class NotificationsComponent {
  private readonly notificationService = inject(NotificationService);

  protected readonly notifications = toSignal(this.notificationService.notifications$, {
    initialValue: [],
  });

  protected close(id: string): void {
    this.notificationService.removeNotification(id);
  }
}

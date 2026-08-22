import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export interface Notification {
  id: string;
  message: string;
  type: 'success' | 'error' | 'warning' | 'info';
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly notificationsSubject = new BehaviorSubject<Notification[]>([]);
  public notifications$ = this.notificationsSubject.asObservable();

  private notificationIdCounter = 0;

  showSuccess(message: string): void {
    this.addNotification(message, 'success');
  }

  showError(message: string): void {
    this.addNotification(message, 'error');
  }

  showWarning(message: string): void {
    this.addNotification(message, 'warning');
  }

  showInfo(message: string): void {
    this.addNotification(message, 'info');
  }

  private addNotification(message: string, type: Notification['type']): void {
    const id = `notification-${++this.notificationIdCounter}`;
    const notification: Notification = { id, message, type };

    const currentNotifications = this.notificationsSubject.value;
    this.notificationsSubject.next([...currentNotifications, notification]);

    // Auto-remove after 4 seconds
    setTimeout(() => this.removeNotification(id), 4000);
  }

  removeNotification(id: string): void {
    const currentNotifications = this.notificationsSubject.value;
    this.notificationsSubject.next(currentNotifications.filter((n) => n.id !== id));
  }
}

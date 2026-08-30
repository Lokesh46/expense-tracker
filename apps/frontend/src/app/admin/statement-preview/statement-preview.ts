import { Component, computed, inject, signal } from '@angular/core';

import { AdminService } from '../../core/services/admin.service';
import { NotificationService } from '../../core/services/notification.service';
import { StatementPreview } from '../../core/models/user.models';
import { describeError } from '../../core/utils/api-error';

/**
 * Shows what the importer sees in a statement, without importing it.
 *
 * For working out why a particular bank's file will not import. Nothing is
 * saved — no transactions, no categories, not even an audit row — so this can
 * be run against a real statement as many times as it takes.
 *
 * Redaction is on by default and worth leaving on: it keeps the header and
 * every column position exactly as they are, and replaces the values, so the
 * result describes the layout without saying anything about the account.
 */
@Component({
  selector: 'app-statement-preview',
  standalone: true,
  templateUrl: './statement-preview.html',
  styleUrl: './statement-preview.css',
})
export class StatementPreviewComponent {
  private readonly admin = inject(AdminService);
  private readonly notifications = inject(NotificationService);

  protected readonly isReading = signal(false);
  protected readonly result = signal<StatementPreview | null>(null);
  protected readonly fileName = signal('');

  /**
   * The PDF password, held only until the request is sent.
   *
   * Never stored and never put in the URL — it travels in the multipart body
   * like the file it opens.
   */
  protected readonly password = signal('');
  protected readonly redact = signal(true);

  /** True when what is on screen was uploaded elsewhere and collected here. */
  protected readonly collected = signal(false);

  /** The extracted text as one block, ready to be copied. */
  protected readonly asText = computed(() => {
    const preview = this.result();
    if (!preview) {
      return '';
    }
    return preview.lines
      .map((line, i) => (i === preview.headerLine ? `${line}      <-- header` : line))
      .join('\n');
  });

  constructor() {
    // A statement is usually on a phone and the person reading the result is
    // not. If one was uploaded elsewhere and is still being held, show it
    // rather than making them upload it again on this device.
    this.admin.lastStatementPreview().subscribe({
      next: (preview) => {
        if (preview) {
          this.result.set(preview);
          this.collected.set(true);
        }
      },
      // Nothing waiting is the ordinary case, and so is the endpoint being
      // switched off. Neither is worth an error.
      error: () => undefined,
    });
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }

    this.fileName.set(file.name);
    this.isReading.set(true);
    this.result.set(null);
    this.collected.set(false);

    this.admin.previewStatement(file, this.password(), this.redact()).subscribe({
      next: (preview) => {
        this.isReading.set(false);
        this.result.set(preview);
        // Allows the same file to be read again after changing an option.
        input.value = '';
      },
      error: (err) => {
        this.isReading.set(false);
        input.value = '';
        this.notifications.showError(describeError(err, 'Could not read that file.'));
      },
    });
  }

  protected async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.notifications.showSuccess('Copied.');
    } catch {
      this.notifications.showError('Could not copy — select the text and copy it manually.');
    }
  }

  /**
   * Clears the screen and forgets the held copy.
   *
   * Both, deliberately: leaving a preview on the server after finishing with it
   * is the one way this tool could hold on to something it should not.
   */
  protected clear(): void {
    this.result.set(null);
    this.collected.set(false);
    this.fileName.set('');
    this.password.set('');
    this.admin.discardStatementPreview().subscribe({ error: () => undefined });
  }
}

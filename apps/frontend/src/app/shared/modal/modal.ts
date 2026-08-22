import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  input,
  output,
  viewChild,
} from '@angular/core';

/**
 * A modal dialog.
 *
 * Handles the parts that are easy to leave out and awkward for a keyboard or
 * screen-reader user when they are missing: focus moves in on open and returns
 * to the trigger on close, Escape dismisses, Tab is trapped inside, and the
 * page behind does not scroll.
 */
@Component({
  selector: 'app-modal',
  standalone: true,
  templateUrl: './modal.html',
  styleUrl: './modal.css',
})
export class ModalComponent implements AfterViewInit, OnDestroy {
  readonly heading = input.required<string>();
  readonly description = input<string>();
  readonly width = input<'narrow' | 'wide'>('narrow');

  readonly closed = output<void>();

  private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
  private previouslyFocused: HTMLElement | null = null;

  ngAfterViewInit(): void {
    this.previouslyFocused = document.activeElement as HTMLElement | null;
    document.body.style.overflow = 'hidden';

    // Prefer the first real control; fall back to the panel so focus is never
    // left behind on the page underneath.
    const target =
      this.panel().nativeElement.querySelector<HTMLElement>(
        'input:not([type=hidden]), select, textarea, button'
      ) ?? this.panel().nativeElement;
    target.focus();
  }

  ngOnDestroy(): void {
    document.body.style.overflow = '';
    this.previouslyFocused?.focus();
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.close();
  }

  // Angular types the host-listener argument as Event, so the concrete type is
  // narrowed here rather than asserted at every use.
  @HostListener('document:keydown.tab', ['$event'])
  @HostListener('document:keydown.shift.tab', ['$event'])
  protected trapFocus(event: Event): void {
    if (!(event instanceof KeyboardEvent)) {
      return;
    }
    const focusable = Array.from(
      this.panel().nativeElement.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([type=hidden]), select, textarea, [tabindex]:not([tabindex="-1"])'
      )
    ).filter((element) => element.offsetParent !== null);

    if (focusable.length === 0) {
      return;
    }

    const first = focusable[0];
    const last = focusable[focusable.length - 1];

    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  protected close(): void {
    this.closed.emit();
  }
}

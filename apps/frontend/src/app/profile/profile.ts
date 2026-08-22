import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class Profile {
  private readonly fb = inject(FormBuilder);

  protected readonly isSaving = signal(false);
  protected readonly saveMessage = signal<string | null>(null);

  readonly profileForm = this.fb.nonNullable.group({
    fullName: ['Priya Sharma', [Validators.required, Validators.minLength(3)]],
    email: ['priya.sharma@example.com', [Validators.required, Validators.email]],
    currency: ['INR', [Validators.required]],
    reminderDay: [5, [Validators.required, Validators.min(1), Validators.max(28)]],
    notifications: this.fb.nonNullable.group({
      email: [true],
      push: [false],
      monthlyDigest: [true],
    }),
  });

  onSubmit(): void {
    this.saveMessage.set(null);
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      return;
    }

    this.isSaving.set(true);

    queueMicrotask(() => {
      setTimeout(() => {
        this.isSaving.set(false);
        this.saveMessage.set('Profile preferences saved successfully.');
      }, 600);
    });
  }

  resetPreferences(): void {
    this.profileForm.reset({
      fullName: 'Priya Sharma',
      email: 'priya.sharma@example.com',
      currency: 'INR',
      reminderDay: 5,
      notifications: {
        email: true,
        push: false,
        monthlyDigest: true,
      },
    });
    this.saveMessage.set('Defaults restored.');
  }
}

import { Component, DestroyRef, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, Validators, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  readonly loginForm = this.fb.nonNullable.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required, Validators.minLength(6)]],
    rememberMe: [false],
  });

  readonly formErrorMessages = {
    username: {
      required: 'Username is required.',
    },
    password: {
      required: 'Password is required.',
      minlength: 'Password must include at least 6 characters.',
    },
  };

  protected submitted = false;
  protected readonly isSubmitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly successMessage = signal<string | null>(null);

  onSubmit(): void {
    this.submitted = true;
    this.errorMessage.set(null);
    this.successMessage.set(null);

    if (this.loginForm.invalid) {
      this.loginForm.markAllAsTouched();
      return;
    }

    const { username, password, rememberMe } = this.loginForm.getRawValue();
    this.isSubmitting.set(true);

    this.authService
      .authenticate({ username, password }, { rememberMe })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isSubmitting.set(false);
          this.successMessage.set('Login successful. Redirecting...');
          setTimeout(() => this.router.navigate(['/home']), 1000);
        },
        error: () => {
          this.isSubmitting.set(false);
          this.errorMessage.set('Invalid credentials. Please try again.');
        },
      });
  }

  showError(controlName: 'username' | 'password'): string | null {
    const control = this.loginForm.get(controlName);
    if (!control) {
      return null;
    }

    if (!(control.invalid && (control.touched || this.submitted))) {
      return null;
    }

    const errors = control.errors ?? {};
    const messages = this.formErrorMessages[controlName];
    return Object.keys(errors)
      .map((key) => messages[key as keyof typeof messages])
      .find(Boolean) ?? null;
  }
}

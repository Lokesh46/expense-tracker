import { Component, DestroyRef, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, Validators, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './register.html',
  styleUrl: './register.css',
})
export class Register {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly isSubmitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly successMessage = signal<string | null>(null);
  private readonly passwordsMatchValidator: ValidatorFn = (group) => {
    const password = group.get('password')?.value;
    const confirmPassword = group.get('confirmPassword')?.value;
    return password && confirmPassword && password !== confirmPassword ? { mismatch: true } : null;
  };

  readonly registerForm = this.fb.nonNullable.group(
    {
      username: ['', [Validators.required]],
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(6)]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: this.passwordsMatchValidator }
  );

  onSubmit(): void {
    this.errorMessage.set(null);
    this.successMessage.set(null);

    if (this.registerForm.invalid) {
      this.registerForm.markAllAsTouched();
      if (this.registerForm.hasError('mismatch')) {
        this.errorMessage.set('Passwords must match.');
      }
      return;
    }

    const { username, password, email } = this.registerForm.getRawValue();
    this.isSubmitting.set(true);

    this.authService
      .register({ username, password, email })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isSubmitting.set(false);
          this.successMessage.set('Registration successful. Redirecting to login...');
          setTimeout(() => this.router.navigate(['/login']), 1400);
        },
        error: (error) => {
          this.isSubmitting.set(false);
          this.errorMessage.set(
            error?.error?.message ?? 'Could not register. Please try again later.'
          );
        },
      });
  }

  showFieldError(controlName: 'username' | 'password' | 'confirmPassword'): string | null {
    const control = this.registerForm.get(controlName);
    if (!control) {
      return null;
    }

    if (
      controlName === 'confirmPassword' &&
      (control.touched || control.dirty) &&
      this.registerForm.hasError('mismatch')
    ) {
      return 'Passwords must match.';
    }

    if (!(control.invalid && (control.touched || control.dirty))) {
      return null;
    }

    if (control.hasError('required')) {
      return 'This field is required.';
    }

    if (controlName === 'password' && control.hasError('minlength')) {
      return 'Password must include at least 6 characters.';
    }

    return null;
  }
}

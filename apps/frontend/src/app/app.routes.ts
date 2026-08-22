import { Routes } from '@angular/router';

import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';

/**
 * Every screen is loaded lazily. Chart.js alone is a large dependency and only
 * the overview needs it, so keeping it out of the initial bundle materially
 * shortens time-to-interactive on the sign-in screen.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },

  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./login/login').then((m) => m.Login),
    title: 'Sign in · Ledger',
  },
  {
    path: 'register',
    canActivate: [guestGuard],
    loadComponent: () => import('./register/register').then((m) => m.Register),
    title: 'Create account · Ledger',
  },

  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./dashboard/dashboard').then((m) => m.DashboardComponent),
    title: 'Overview · Ledger',
  },
  {
    path: 'transactions',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./transactions/transactions').then((m) => m.TransactionsComponent),
    title: 'Ledger · Ledger',
  },
  {
    path: 'budgets',
    canActivate: [authGuard],
    loadComponent: () => import('./budgets/budgets').then((m) => m.BudgetsComponent),
    title: 'Budgets · Ledger',
  },
  {
    path: 'categories',
    canActivate: [authGuard],
    loadComponent: () => import('./categories/categories').then((m) => m.CategoriesComponent),
    title: 'Categories · Ledger',
  },
  {
    path: 'recurring',
    canActivate: [authGuard],
    loadComponent: () => import('./recurring/recurring').then((m) => m.RecurringComponent),
    title: 'Recurring · Ledger',
  },
  {
    path: 'profile',
    canActivate: [authGuard],
    loadComponent: () => import('./profile/profile').then((m) => m.Profile),
    title: 'Account · Ledger',
  },

  { path: '**', redirectTo: 'dashboard' },
];

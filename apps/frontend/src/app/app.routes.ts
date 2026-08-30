import { Routes } from '@angular/router';

import { adminGuard } from './core/guards/admin.guard';
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
    path: 'review',
    canActivate: [authGuard],
    loadComponent: () => import('./review/review').then((m) => m.ReviewComponent),
    title: 'Needs review · Ledger',
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
    path: 'category-rules',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./category-rules/category-rules').then((m) => m.CategoryRulesComponent),
    title: 'Filing rules · Ledger',
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

  /**
   * Administration. Guarded twice: signed in at all, and an administrator.
   *
   * Both guards are conveniences — the API checks the stored role on every
   * request regardless — so their job is to keep a member off a page that would
   * otherwise fill with refusals.
   */
  {
    path: 'admin/users',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./admin/users/users').then((m) => m.AdminUsersComponent),
    title: 'Users · Ledger',
  },
  {
    path: 'admin/users/:id',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./admin/user-detail/user-detail').then((m) => m.AdminUserDetailComponent),
    title: 'Account · Ledger',
  },
  {
    // A temporary diagnostic: the backend endpoint it calls is off unless
    // app.admin.statement-preview is switched on, so the page reports a 404
    // rather than working when the tool is not meant to be available.
    path: 'admin/statement-preview',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./admin/statement-preview/statement-preview').then(
        (m) => m.StatementPreviewComponent
      ),
    title: 'Statement preview · Ledger',
  },
  {
    path: 'admin/activity',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./admin/activity/activity').then((m) => m.AdminActivityComponent),
    title: 'Activity · Ledger',
  },
  { path: 'admin', pathMatch: 'full', redirectTo: 'admin/users' },

  { path: '**', redirectTo: 'dashboard' },
];

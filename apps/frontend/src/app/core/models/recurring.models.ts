export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

/** A rule that generates transactions on a schedule. */
export interface RecurringTransaction {
  id: number;
  categoryId: number;
  categoryName: string;
  description: string;
  amount: number;
  currency: string;
  paymentMethod: string;
  comments?: string;
  frequency: Frequency;
  /** The next date this rule still owes a transaction for. */
  nextRunDate: string;
  endDate?: string | null;
  active: boolean;
}

export interface SaveRecurringRequest {
  categoryId: number;
  description: string;
  amount: number;
  currency: string;
  paymentMethod: string;
  comments?: string;
  frequency: Frequency;
  nextRunDate: string;
  endDate?: string | null;
  active: boolean;
}

export const FREQUENCIES: { value: Frequency; label: string }[] = [
  { value: 'DAILY', label: 'Every day' },
  { value: 'WEEKLY', label: 'Every week' },
  { value: 'MONTHLY', label: 'Every month' },
  { value: 'YEARLY', label: 'Every year' },
];

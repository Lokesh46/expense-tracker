/**
 * Mirrors TransactionDTO on the backend.
 *
 * `amount` arrives as a JSON number and `date` as an ISO calendar date
 * (yyyy-MM-dd) — the backend uses BigDecimal and LocalDate respectively.
 */
export interface Transaction {
  id: number;
  userId: number;
  categoryId: number;
  categoryName: string;
  description: string;
  amount: number;
  currency: string;
  date: string;
  paymentMethod: string;
  comments?: string;
}

export interface CreateTransactionRequest {
  categoryId: number;
  description: string;
  amount: number;
  currency: string;
  date: string;
  paymentMethod: string;
  comments?: string;
}

export type UpdateTransactionRequest = CreateTransactionRequest;

/** The page envelope returned by every paged endpoint. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type SortDirection = 'asc' | 'desc';

/**
 * Search parameters. Filtering, sorting and paging all happen in the database,
 * so this maps directly onto the query string.
 */
export interface TransactionQuery {
  page?: number;
  size?: number;
  sortBy?: 'date' | 'amount' | 'description';
  sortDir?: SortDirection;
  categoryId?: number | null;
  from?: string | null;
  to?: string | null;
  minAmount?: number | null;
  maxAmount?: number | null;
  paymentMethod?: string | null;
  search?: string | null;
}

export interface ImportResult {
  imported: number;
  skipped: number;
  errors: string[];
}

export const PAYMENT_METHODS = [
  'Cash',
  'Credit Card',
  'Debit Card',
  'Bank Transfer',
  'UPI',
  'Other',
] as const;

export const CURRENCIES = ['GBP', 'USD', 'EUR', 'INR', 'AUD', 'CAD'] as const;

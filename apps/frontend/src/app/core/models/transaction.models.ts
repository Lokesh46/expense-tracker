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
  /** Money out or money in. The amount itself is always positive. */
  type: TransactionType;
  currency: string;
  date: string;
  paymentMethod: string;
  comments?: string;

  /**
   * Set by an import that found a transaction already on file matching this
   * one. The row is real and counts toward every total; the flag only means it
   * is worth a second look.
   */
  possibleDuplicate: boolean;
}

/**
 * Which way the money went. The amount is stored as a positive number either
 * way, so this is what distinguishes a refund from a purchase.
 */
export type TransactionType = 'EXPENSE' | 'INCOME';

export interface CreateTransactionRequest {
  categoryId: number;
  description: string;
  amount: number;
  type: TransactionType;
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

  /** Restricts to flagged rows. Undefined means no restriction, not false. */
  possibleDuplicate?: boolean | null;

  /** Restricts to expenses or income. Undefined means both. */
  type?: TransactionType | null;
}

/**
 * Which of the day and the month comes first in a slash-separated date.
 *
 * 03/04/2026 is the 3rd of April in most of the world and the 4th of March in
 * the United States, and nothing in a file says which. Reading it the wrong way
 * is not a visible error — it is a year of spending filed into the wrong months
 * — so the import asks rather than guesses.
 */
export type DateOrder = 'DAY_FIRST' | 'MONTH_FIRST';

export interface ImportResult {
  /** Rows written, including any that were flagged. */
  imported: number;
  /** Rows that could not be read at all. */
  skipped: number;
  /** Rows written that match one already on file. A subset of `imported`. */
  flagged: number;
  errors: string[];
  /**
   * How the file's columns were understood, in plain words. Always shown: a
   * mapping that guessed wrong is worse than one that failed, and the user is
   * the only one who can tell that "Reference" was the wrong column to read as
   * the description.
   */
  columnMapping: string;
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

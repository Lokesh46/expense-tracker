import { CategorySource } from './transaction.models';

/** A total in one currency. Never added to a total in another. */
export interface CurrencyTotal {
  currency: string;
  amount: number;
}

/**
 * One merchant's worth of imported rows waiting to be approved or refiled.
 *
 * The unit of review is the merchant, not the row. A statement of two hundred
 * rows is usually a handful of shops, and reviewing it row by row is what makes
 * people give up on categorising altogether.
 */
export interface MerchantGroup {
  /**
   * How the group is addressed when acting on it — a digest, not a name.
   * Derived from the account's own key, so it means nothing in another account.
   */
  merchantHash: string;
  merchantName: string;
  transactionCount: number;
  totals: CurrencyTotal[];
  suggestedCategoryId: number | null;
  suggestedCategoryName: string | null;
  /** How the suggestion was arrived at, so a guess reads differently from a fallback. */
  source: CategorySource;
  /**
   * The earliest and latest descriptions in the group. Two, so that a merchant
   * key which has merged two different shops shows it before you approve a
   * hundred rows at once.
   */
  samples: string[];
}

export interface ReviewQueue {
  merchants: MerchantGroup[];
  /** Groups altogether, which may exceed those listed. */
  merchantsTotal: number;
  /** Rows waiting, across every group. */
  transactions: number;
}

export interface MerchantAssignment {
  categoryId: number;
  /** Whether to remember the decision as a filing rule. */
  createRule: boolean;
}

export interface ReviewAction {
  updated: number;
  ruleCreated: boolean;
  /** Wording for the user, including the case where the rule was not written. */
  message: string;
}

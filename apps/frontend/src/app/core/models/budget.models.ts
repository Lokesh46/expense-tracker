/**
 * A monthly cap for one category.
 *
 * The limit is stored; `spent`, `remaining`, `percentUsed` and `exceeded` are
 * computed by the backend for whichever month was requested.
 */
export interface Budget {
  id: number;
  categoryId: number;
  categoryName: string;
  monthlyLimit: number;
  spent: number;
  remaining: number;
  percentUsed: number;
  exceeded: boolean;
}

export interface SaveBudgetRequest {
  categoryId: number;
  monthlyLimit: number;
}

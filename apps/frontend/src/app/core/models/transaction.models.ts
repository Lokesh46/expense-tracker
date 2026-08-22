/**
 * Mirrors TransactionDTO on the backend. Ids are Java Integers, and `date` is
 * serialised by Jackson as an ISO-8601 timestamp.
 *
 * The backend does not track created/updated timestamps, so they are not
 * declared here.
 */
export interface Transaction {
  id: number;
  userId: number;
  categoryId: number;
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

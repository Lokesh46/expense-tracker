export interface Transaction {
  id: string;
  amount: number;
  categoryId: string;
  description: string;
  currency: string;
  date: string;
  paymentMethod: string;
  comments?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateTransactionRequest {
  amount: number;
  categoryId: string;
  description: string;
  currency: string;
  date: string;
  paymentMethod: string;
  comments?: string;
}

export interface UpdateTransactionRequest {
  amount: number;
  categoryId: string;
  description: string;
  currency: string;
  date: string;
  paymentMethod: string;
  comments?: string;
}

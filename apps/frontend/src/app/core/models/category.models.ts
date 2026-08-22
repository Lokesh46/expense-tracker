/** Mirrors CategoryDTO. Categories are owned by a single user. */
export interface Category {
  id: number;
  name: string;
  color: string;
}

export interface CreateCategoryRequest {
  name: string;
  color?: string;
}

export type UpdateCategoryRequest = CreateCategoryRequest;

/** Offered in the colour picker when creating a category. */
export const CATEGORY_COLORS = [
  '#e0a959',
  '#56b8a4',
  '#e58267',
  '#7aa2f7',
  '#c9a0dc',
  '#8bc34a',
  '#f2957f',
  '#6fb3c9',
  '#d4a373',
  '#9aa899',
] as const;

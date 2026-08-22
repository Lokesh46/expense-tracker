/** Mirrors CategoryDTO on the backend, where the id is a Java Integer. */
export interface Category {
  id: number;
  name: string;
}

export interface CreateCategoryRequest {
  name: string;
}

export interface UpdateCategoryRequest {
  name: string;
}

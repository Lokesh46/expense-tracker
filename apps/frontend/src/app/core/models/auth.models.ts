export interface AuthRequest {
  username: string;
  password: string;
}

export interface AuthResponse {
  token: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email?: string;
  role?: string;
  active?: boolean;
}

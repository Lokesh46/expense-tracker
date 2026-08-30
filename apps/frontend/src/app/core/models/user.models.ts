/**
 * User management, mirroring the backend's admin and account DTOs.
 *
 * Note what is not here: nothing about anybody's money. An administrator manages
 * accounts and sees counts, never contents, and there is no endpoint that would
 * fill in a field for it.
 */

export type Role = 'ADMIN' | 'MEMBER';

/** Derived server-side from `active` and the lockout expiry. */
export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'LOCKED';

export type ActivityAction =
  | 'LOGIN_SUCCEEDED'
  | 'LOGIN_FAILED'
  | 'ACCOUNT_LOCKED'
  | 'ACCOUNT_UNLOCKED'
  | 'REGISTERED'
  | 'USER_CREATED'
  | 'ACCOUNT_DELETED'
  | 'ROLE_CHANGED'
  | 'ACCOUNT_SUSPENDED'
  | 'ACCOUNT_REINSTATED'
  | 'EMAIL_CHANGED'
  | 'PASSWORD_CHANGED'
  | 'PASSWORD_RESET'
  | 'SESSIONS_REVOKED';

/** A row in the user list. Timestamps arrive as ISO-8601 instants. */
export interface UserSummary {
  id: number;
  username: string;
  email: string | null;
  role: Role;
  status: AccountStatus;
  createdAt: string;
  lastLoginAt: string | null;
  loginCount: number;
  lockedUntil: string | null;
}

/**
 * One entry from the audit trail.
 *
 * `label` and `adverse` are computed by the backend rather than mapped here, so
 * the wording and the "this is a problem" judgement have exactly one home. A
 * client-side copy of the enum drifts the first time an action is added.
 */
export interface ActivityEntry {
  id: number;
  occurredAt: string;
  action: ActivityAction;
  label: string;
  adverse: boolean;
  username: string;
  actor: string | null;
  detail: string | null;
  ipAddress: string | null;
  userAgent: string | null;
}

export interface UserDetail {
  account: UserSummary;
  failedLoginAttempts: number;
  lastLoginIp: string | null;
  transactionCount: number;
  categoryCount: number;
  budgetCount: number;
  recurringCount: number;
  recentActivity: ActivityEntry[];
}

export interface AdminStats {
  totalUsers: number;
  admins: number;
  members: number;
  active: number;
  suspended: number;
  locked: number;
  joinedLast7Days: number;
  joinedLast30Days: number;
  signInsLast24Hours: number;
  failedSignInsLast24Hours: number;
}

/** Your own account, from `/api/account/me`. */
export interface Account {
  id: number;
  username: string;
  email: string | null;
  role: Role;
  status: AccountStatus;
  createdAt: string;
  lastLoginAt: string | null;
  lastLoginIp: string | null;
  loginCount: number;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  email?: string;
  role: Role;
  active?: boolean;
}

/**
 * A partial update: an omitted field is left alone.
 *
 * That distinction is the reason the endpoint is a PATCH. Sending the whole
 * object back would make two administrators editing different fields overwrite
 * each other, and would make clearing an email indistinguishable from not
 * touching it.
 */
export interface UpdateUserRequest {
  email?: string;
  role?: Role;
  active?: boolean;
}

export interface UserQuery {
  page?: number;
  size?: number;
  sortBy?: 'username' | 'createdAt' | 'lastLoginAt' | 'loginCount' | 'role';
  sortDir?: 'asc' | 'desc';
  search?: string | null;
  role?: Role | null;
  status?: AccountStatus | null;
}

export interface ActivityQuery {
  page?: number;
  size?: number;
  username?: string | null;
  action?: ActivityAction | null;
  from?: string | null;
  to?: string | null;
  adverseOnly?: boolean | null;
}

/** Labels for the filter dropdowns, in the order they read best. */
export const ACTIVITY_ACTIONS: ReadonlyArray<{ value: ActivityAction; label: string }> = [
  { value: 'LOGIN_SUCCEEDED', label: 'Signed in' },
  { value: 'LOGIN_FAILED', label: 'Failed sign-in' },
  { value: 'ACCOUNT_LOCKED', label: 'Locked out' },
  { value: 'ACCOUNT_UNLOCKED', label: 'Unlocked' },
  { value: 'REGISTERED', label: 'Registered' },
  { value: 'USER_CREATED', label: 'Created by an admin' },
  { value: 'ROLE_CHANGED', label: 'Role changed' },
  { value: 'ACCOUNT_SUSPENDED', label: 'Suspended' },
  { value: 'ACCOUNT_REINSTATED', label: 'Reinstated' },
  { value: 'EMAIL_CHANGED', label: 'Email changed' },
  { value: 'PASSWORD_CHANGED', label: 'Password changed' },
  { value: 'PASSWORD_RESET', label: 'Password reset' },
  { value: 'SESSIONS_REVOKED', label: 'Sessions revoked' },
  { value: 'ACCOUNT_DELETED', label: 'Deleted' },
];

export const ROLE_LABELS: Readonly<Record<Role, string>> = {
  ADMIN: 'Admin',
  MEMBER: 'Member',
};

export const STATUS_LABELS: Readonly<Record<AccountStatus, string>> = {
  ACTIVE: 'Active',
  SUSPENDED: 'Suspended',
  LOCKED: 'Locked',
};

/**
 * What the importer makes of a statement, without importing it.
 *
 * A diagnostic for a file that will not import. Nothing is stored: the upload is
 * read in memory, described, and dropped.
 */
export interface StatementPreview {
  /** The extracted text, one entry per line, with spacing preserved. */
  lines: string[];
  /** Index into `lines` of the row taken as the header, or -1 if none. */
  headerLine: number;
  rowsDetected: number;
  /** The table rewritten as CSV — what the importer would actually read. */
  csv: string;
  columnMapping: string;
  /** Whether values were replaced with placeholders. Layout survives; contents do not. */
  redacted: boolean;
}

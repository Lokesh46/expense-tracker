/**
 * Mirrors CategoryRuleDTO.
 *
 * A rule decides which category an imported transaction is filed under, based
 * on its description. Rules are owned by one user and never shared.
 */
export interface CategoryRule {
  id: number;
  pattern: string;
  matchType: MatchType;
  categoryId: number;
  categoryName: string;
  /** Lowest first. The first matching rule wins, so this is the whole meaning. */
  priority: number;
  active: boolean;
}

/**
 * How a rule compares its pattern against a description.
 *
 * There is deliberately no regular-expression option: a user-supplied
 * expression evaluated on the server against every row of an import is a
 * denial-of-service waiting to happen. See MatchType on the backend.
 */
export type MatchType = 'CONTAINS' | 'STARTS_WITH' | 'EQUALS';

export interface CreateCategoryRuleRequest {
  pattern: string;
  matchType: MatchType;
  categoryId: number;
  active: boolean;
}

export type UpdateCategoryRuleRequest = CreateCategoryRuleRequest & {
  /** Preserved on update; reordering goes through the move endpoints instead. */
  priority?: number;
};

/** The wording for each match type, fetched from the server rather than duplicated. */
export interface MatchTypeOption {
  value: MatchType;
  label: string;
}

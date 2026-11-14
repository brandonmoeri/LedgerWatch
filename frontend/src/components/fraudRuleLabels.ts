const RULE_LABELS: Record<string, string> = {
  VELOCITY: 'Velocity',
  AMOUNT_SPIKE: 'Amount spike',
  NEW_ACCOUNT_LARGE_DEBIT: 'Large debit, new account',
};

/** A readable name for a fraud rule. A rule this build doesn't know shows as its raw name. */
export function fraudRuleLabel(rule: string): string {
  return RULE_LABELS[rule] ?? rule;
}

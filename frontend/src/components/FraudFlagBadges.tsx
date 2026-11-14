import type { TransactionFlag } from '../types/fraud';
import { fraudRuleLabel } from './fraudRuleLabels';

export interface FraudFlagBadgesProps {
  flags: TransactionFlag[];
}

/** One badge per fraud rule that fired. The rule's detail is in the tooltip and read by screen readers. */
export default function FraudFlagBadges({ flags }: FraudFlagBadgesProps) {
  if (flags.length === 0) {
    return null;
  }
  return (
    <ul className="flag-badges" aria-label="Fraud flags">
      {flags.map((flag) => (
        <li key={flag.rule}>
          <span className="flag-badge" title={flag.detail}>
            <span aria-hidden="true">⚑ </span>
            {fraudRuleLabel(flag.rule)}
            <span className="sr-only">: {flag.detail}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}

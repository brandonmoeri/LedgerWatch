import type { TransactionRejectionReason, TransactionStatus } from '../types/transaction';

const REJECTION_LABELS: Record<TransactionRejectionReason, string> = {
  ACCOUNT_NOT_FOUND: 'Account not found',
  ACCOUNT_NOT_ACTIVE: 'Account is frozen or closed',
  INSUFFICIENT_FUNDS: 'Insufficient funds',
};

export interface TransactionStatusBadgeProps {
  status: TransactionStatus;
  rejectionReason?: TransactionRejectionReason | null;
}

export default function TransactionStatusBadge({
  status,
  rejectionReason,
}: TransactionStatusBadgeProps) {
  return (
    <>
      <span className={`status-badge status-badge--${status.toLowerCase()}`}>{status}</span>
      {status === 'REJECTED' && rejectionReason && (
        <span className="status-reason">{REJECTION_LABELS[rejectionReason]}</span>
      )}
    </>
  );
}

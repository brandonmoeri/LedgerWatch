import { useCallback, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { fetchAccountFlags } from '../store/accountFlagsSlice';
import type { AppDispatch, RootState } from '../store/store';
import type { FraudFlag } from '../types/fraud';
import DataTable from './DataTable';
import type { DataTableColumn } from './DataTable';
import { fraudRuleLabel } from './fraudRuleLabels';
import { useStableCallback } from '../hooks/useStableCallback';

const COLUMNS: DataTableColumn<FraudFlag>[] = [
  { key: 'flaggedAt', header: 'Flagged', render: (f) => new Date(f.flaggedAt).toLocaleString() },
  { key: 'rule', header: 'Rule', render: (f) => <span className="flag-badge">{fraudRuleLabel(f.rule)}</span> },
  { key: 'detail', header: 'Detail', render: (f) => f.detail },
  {
    key: 'outcome',
    header: 'Outcome',
    render: (f) =>
      f.blocked ? (
        <span className="status-badge status-badge--rejected">BLOCKED</span>
      ) : (
        <span className="status-badge status-badge--voided" title="Posted anyway: the rules ran in monitor mode">
          MONITORED
        </span>
      ),
  },
  {
    key: 'transaction',
    header: 'Transaction',
    render: (f) => <code title={f.transactionId}>{f.transactionId.slice(0, 8)}</code>,
  },
];

export interface AccountFlagsPanelProps {
  accountId: string;
}

/** The fraud flags raised on an account's transactions, newest first. */
export default function AccountFlagsPanel({ accountId }: AccountFlagsPanelProps) {
  const dispatch = useDispatch<AppDispatch>();
  const flags = useSelector((s: RootState) => s.accountFlags);

  useEffect(() => {
    dispatch(fetchAccountFlags({ accountId }));
  }, [accountId, dispatch]);

  // Before this account's first request starts, the state can still hold the previous account's flags.
  const status = flags.accountId === accountId ? flags.status : 'loading';

  const handlePageChange = useStableCallback((page: number) => {
    dispatch(fetchAccountFlags({ accountId, page }));
  });
  const handleRetry = useStableCallback(() => {
    dispatch(fetchAccountFlags({ accountId, page: flags.page }));
  });
  const getRowKey = useCallback((f: FraudFlag) => f.id, []);

  return (
    <section className="flags-panel" aria-labelledby="account-flags-heading">
      <h2 id="account-flags-heading">Fraud flags</h2>
      <DataTable
        caption="Fraud flags"
        columns={COLUMNS}
        items={flags.items}
        getRowKey={getRowKey}
        status={status}
        error={flags.error}
        page={flags.page}
        totalPages={flags.totalPages}
        onPageChange={handlePageChange}
        onRetry={handleRetry}
        emptyMessage="No fraud flags on this account."
      />
    </section>
  );
}

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link } from 'react-router-dom';
import { fetchAccounts } from '../store/accountsSlice';
import type { AppDispatch, RootState } from '../store/store';
import type { Account, AccountStatus } from '../types/account';
import DataTable from '../components/DataTable';
import type { DataTableColumn, DataTableSort } from '../components/DataTable';
import { useStableCallback } from '../hooks/useStableCallback';

const COLUMNS: DataTableColumn<Account>[] = [
  { key: 'owner', header: 'Owner', render: (account) => account.ownerName, sortField: 'ownerName' },
  { key: 'status', header: 'Status', render: (account) => account.status },
  { key: 'balance', header: 'Balance', render: (account) => account.balance, sortField: 'balance' },
  {
    key: 'created',
    header: 'Created',
    render: (account) => new Date(account.createdAt).toLocaleDateString(),
    sortField: 'createdAt',
  },
  {
    key: 'view',
    header: '',
    render: (account) => <Link to={`/accounts/${account.id}`}>View</Link>,
  },
];

const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'ownerName,asc', label: 'Owner (A-Z)' },
  { value: 'ownerName,desc', label: 'Owner (Z-A)' },
  { value: 'balance,desc', label: 'Balance (high to low)' },
  { value: 'balance,asc', label: 'Balance (low to high)' },
];

export default function AccountsDashboardPage() {
  const dispatch = useDispatch<AppDispatch>();
  const { items, status, error, page, totalPages } = useSelector((s: RootState) => s.accounts);

  const [statusFilter, setStatusFilter] = useState<AccountStatus | ''>('');
  const [ownerNameFilter, setOwnerNameFilter] = useState('');
  const [sort, setSort] = useState('createdAt,desc');

  useEffect(() => {
    if (status === 'idle') {
      dispatch(fetchAccounts({ page: 0, sort }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, dispatch]);

  const runSearch = (nextPage: number, nextSort: string) => {
    dispatch(fetchAccounts({
      page: nextPage,
      sort: nextSort,
      status: statusFilter || undefined,
      ownerName: ownerNameFilter || undefined,
    }));
  };

  const handleSearchSubmit = (e: FormEvent) => {
    e.preventDefault();
    runSearch(0, sort);
  };

  const handleSortChange = (value: string) => {
    setSort(value);
    runSearch(0, value);
  };

  const [sortField, sortDirectionRaw] = sort.split(',');
  const sortDirection = sortDirectionRaw === 'asc' ? 'asc' : 'desc';

  const handleHeaderSort = useStableCallback((field: string) => {
    const nextDirection = field === sortField && sortDirection === 'asc' ? 'desc' : 'asc';
    handleSortChange(`${field},${nextDirection}`);
  });

  // Stable identities so DataTable (React.memo'd) doesn't re-render its rows
  // whenever unrelated page state (e.g. filter text) changes.
  const handlePageChange = useStableCallback((nextPage: number) => runSearch(nextPage, sort));
  const handleRetry = useStableCallback(() => runSearch(page, sort));
  const getRowKey = useCallback((account: Account) => account.id, []);
  const tableSort = useMemo<DataTableSort>(
    () => ({ field: sortField, direction: sortDirection }),
    [sortField, sortDirection]
  );

  return (
    <div>
      <h1>Accounts Dashboard</h1>
      <Link to="/accounts/new">+ New Account</Link>
      {' | '}
      <Link to="/dashboard">View spending dashboard</Link>

      <form onSubmit={handleSearchSubmit}>
        <label>
          Status
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as AccountStatus | '')}
          >
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="FROZEN">Frozen</option>
            <option value="CLOSED">Closed</option>
          </select>
        </label>
        <label>
          Owner name
          <input
            type="text"
            value={ownerNameFilter}
            onChange={(e) => setOwnerNameFilter(e.target.value)}
          />
        </label>
        <label>
          Sort
          <select value={sort} onChange={(e) => handleSortChange(e.target.value)}>
            {SORT_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </label>
        <button type="submit">Search</button>
      </form>

      <DataTable
        caption="Accounts"
        columns={COLUMNS}
        items={items}
        getRowKey={getRowKey}
        status={status}
        error={error}
        page={page}
        totalPages={totalPages}
        onPageChange={handlePageChange}
        onRetry={handleRetry}
        emptyMessage="No accounts found."
        sort={tableSort}
        onSortChange={handleHeaderSort}
      />
    </div>
  );
}

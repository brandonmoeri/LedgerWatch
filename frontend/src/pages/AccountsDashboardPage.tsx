import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link } from 'react-router-dom';
import { fetchAccounts } from '../store/accountsSlice';
import type { AppDispatch, RootState } from '../store/store';
import type { AccountStatus } from '../types/account';

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

  return (
    <div>
      <h1>Accounts Dashboard</h1>
      <Link to="/accounts/new">+ New Account</Link>

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

      {status === 'loading' && <p>Loading…</p>}
      {status === 'failed' && <p>Error: {error}</p>}
      {status === 'succeeded' && (
        <>
          <table>
            <thead>
              <tr>
                <th>Owner</th>
                <th>Status</th>
                <th>Balance</th>
                <th>Created</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((account) => (
                <tr key={account.id}>
                  <td>{account.ownerName}</td>
                  <td>{account.status}</td>
                  <td>{account.balance}</td>
                  <td>{new Date(account.createdAt).toLocaleDateString()}</td>
                  <td>
                    <Link to={`/accounts/${account.id}`}>View</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div>
            <button onClick={() => runSearch(Math.max(page - 1, 0), sort)} disabled={page === 0}>
              Previous
            </button>
            <span>Page {page + 1} of {Math.max(totalPages, 1)}</span>
            <button onClick={() => runSearch(page + 1, sort)} disabled={page + 1 >= totalPages}>
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}

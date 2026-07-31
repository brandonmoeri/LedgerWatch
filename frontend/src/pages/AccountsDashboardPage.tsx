import { useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link } from 'react-router-dom';
import { fetchAccounts } from '../store/accountsSlice';
import type { AppDispatch, RootState } from '../store/store';

export default function AccountsDashboardPage() {
  const dispatch = useDispatch<AppDispatch>();
  const { items, status, error } = useSelector((s: RootState) => s.accounts);

  useEffect(() => {
    if (status === 'idle') dispatch(fetchAccounts());
  }, [status, dispatch]);

  return (
    <div>
      <h1>Accounts Dashboard</h1>
      <Link to="/accounts/new">+ New Account</Link>

      {status === 'loading' && <p>Loading…</p>}
      {status === 'failed' && <p>Error: {error}</p>}
      {status === 'succeeded' && (
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
      )}
    </div>
  );
}
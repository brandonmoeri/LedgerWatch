import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link, useParams } from 'react-router-dom';
import { fetchAccountById, updateAccount } from '../store/accountsSlice';
import type { AppDispatch, RootState } from '../store/store';
import type { AccountStatus } from '../types/account';
import Skeleton from '../components/Skeleton';

export default function AccountDetailPage() {
    const { id } = useParams<{ id: string }>();
    const dispatch = useDispatch<AppDispatch>();
    const account = useSelector((s: RootState) => s.accounts.selectedAccount);
    const selectedStatus = useSelector((s: RootState) => s.accounts.selectedStatus);
    const error = useSelector((s: RootState) => s.accounts.error);

    const [ownerName, setOwnerName] = useState('');
    const [status, setStatus] = useState<AccountStatus>('ACTIVE');
    const [editing, setEditing] = useState(false);
    const [syncedAccountId, setSyncedAccountId] = useState<string | undefined>(undefined);

    useEffect(() => {
        if (id) dispatch(fetchAccountById(id));
    }, [id, dispatch]);

    // sync form fields when account loads
    if (account && account.id !== syncedAccountId) {
        setSyncedAccountId(account.id);
        setOwnerName(account.ownerName);
        setStatus(account.status);
    }

    function handleSave() {
        if (!id) return;
        dispatch(updateAccount({ id, body: { ownerName, status } })).then(() =>
            setEditing(false)
        );
    }

    if (selectedStatus === 'loading' || (selectedStatus === 'idle' && !account)) {
        return (
            <div aria-busy="true" aria-live="polite">
                <span className="sr-only">Loading…</span>
                <Link to="/accounts">← Back</Link>
                <h1><Skeleton width="12rem" height="1.5em" /></h1>
                <p><Skeleton width="8rem" /></p>
                <p><Skeleton width="8rem" /></p>
                <p><Skeleton width="12rem" /></p>
            </div>
        );
    }

    if (selectedStatus === 'failed') {
        return (
            <div role="alert" className="state-error">
                <Link to="/accounts">← Back</Link>
                <p>Error: {error ?? 'Failed to load account.'}</p>
                {id && <button onClick={() => dispatch(fetchAccountById(id))}>Retry</button>}
            </div>
        );
    }

    if (!account) return null;

    return (
        <div>
            <Link to="/accounts">← Back</Link>
            {editing ? (
                <>
                    <input value={ownerName} onChange={(e) => setOwnerName(e.target.value)} />
                    <select
                        value={status}
                        onChange={(e) => setStatus(e.target.value as AccountStatus)}
                    >
                        <option value="ACTIVE">ACTIVE</option>
                        <option value="FROZEN">FROZEN</option>
                        <option value="CLOSED">CLOSED</option>
                    </select>
                    <button onClick={handleSave}>Save</button>
                    <button onClick={() => setEditing(false)}>Cancel</button>
                </>
            ) : (
                <>
                    <h1>{account.ownerName}</h1>
                    <p>Status: {account.status}</p>
                    <p>Balance: {account.balance}</p>
                    <p>Created: {new Date(account.createdAt).toLocaleString()}</p>
                    <button onClick={() => setEditing(true)}>Edit</button>
                </>
            )}
        </div>
    );
}
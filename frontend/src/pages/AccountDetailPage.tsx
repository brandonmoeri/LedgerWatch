import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link, useParams } from 'react-router-dom';
import { fetchAccountById, updateAccount } from '../store/accountsSlice';
import type { AppDispatch, RootState } from '../store/store';
import type { AccountStatus } from '../types/account';

export default function AccountDetailPage() {
    const { id } = useParams<{ id: string }>();
    const dispatch = useDispatch<AppDispatch>();
    const account = useSelector((s: RootState) => s.accounts.selectedAccount);
    const selectedStatus = useSelector((s: RootState) => s.accounts.selectedStatus);

    const [ownerName, setOwnerName] = useState('');
    const [status, setStatus] = useState<AccountStatus>('ACTIVE');
    const [editing, setEditing] = useState(false);

    useEffect(() => {
        if (id) dispatch(fetchAccountById(id));
    }, [id, dispatch]);

    // sync form fields when account loads
    useEffect(() => {
        if (account) {
            setOwnerName(account.ownerName);
            setStatus(account.status);
        }
    }, [account]);

    function handleSave() {
        if (!id) return;
        dispatch(updateAccount({ id, body: { ownerName, status } })).then(() =>
            setEditing(false)
        );
    }

    if (selectedStatus === 'loading' || !account) return <p>Loading…</p>;
    if (selectedStatus === 'failed') return <p>Failed to load account.</p>;

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
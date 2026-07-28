import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { accountsApi } from '../api/accounts';
import type { ApiError } from '../api/client';

export default function CreateAccountPage() {
    const navigate = useNavigate();
    const [ownerName, setOwnerName] = useState('');
    const [initialBalance, setInitialBalance] = useState('');
    const [error, setError] = useState<ApiError | null>(null);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        try {
            const account = await accountsApi.create({
                ownerName,
                initialBalance: initialBalance || null,
            });
            navigate(`/accounts/${account.id}`);
        } catch (err) {
            setError(err as ApiError);
        }
    }

    return (
        <form onSubmit={handleSubmit}>
            <h1>Create Account</h1>
            <input value={ownerName} onChange={(e) => setOwnerName(e.target.value)} placeholder="Owner Name" required />
            <input value={initialBalance} onChange={(e) => setInitialBalance(e.target.value)} placeholder="Initial Balance" />
            <button type="submit">Create</button>
            {error && <p>{error.detail}</p>}
            {error?.errors && <ul>{Object.entries(error.errors).map(([f, m]) => <li key={f}>{f}: {m}</li>)}</ul>}
        </form>
    );
}
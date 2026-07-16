import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { accountsApi } from '../api/accounts';
import type { Account } from '../types/account';

export default function AccountDetailPage() {
    const { id } = useParams<{ id: string }>();
    const [account, setAccount] = useState<Account | null>(null);

    useEffect(() => {
        if (id) accountsApi.getById(id).then(setAccount);
    }, [id]);

    if (!account) return <p>Loading...</p>

    return (
        <div>
            <h1>{account.ownerName}</h1>
            <p>Status: {account.status}</p>
            <p>Balance: {account.balance}</p>
            <p>Created: {new Date(account.createdAt).toLocaleString()}</p>
        </div>
    );
}
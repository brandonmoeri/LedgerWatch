export type TransactionType = 'CREDIT' | 'DEBIT';

export type TransactionStatus = 'PENDING' | 'POSTED' | 'VOIDED';

export interface Transaction {
    id: string;
    accountId: string;
    type: TransactionType;
    status: TransactionStatus;
    amount: string;
    description: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface TransactionsQuery {
    page?: number;
    size?: number;
    sort?: string;
    accountId?: string;
    type?: TransactionType;
    status?: TransactionStatus;
    description?: string;
    createdFrom?: string;
    createdTo?: string;
}

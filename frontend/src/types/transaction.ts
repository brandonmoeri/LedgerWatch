export type TransactionType = 'CREDIT' | 'DEBIT';

export type TransactionStatus = 'PENDING' | 'POSTED' | 'REJECTED' | 'VOIDED';

export type TransactionRejectionReason =
    | 'ACCOUNT_NOT_FOUND'
    | 'ACCOUNT_NOT_ACTIVE'
    | 'INSUFFICIENT_FUNDS'
    | 'SUSPECTED_FRAUD';

export interface Transaction {
    id: string;
    accountId: string;
    type: TransactionType;
    status: TransactionStatus;
    rejectionReason: TransactionRejectionReason | null;
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

export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
    id: string;
    ownerName: string;
    status: AccountStatus;
    balance: string;
    createdAt: string;
    updatedAt: string;
}

export interface CreateAccountRequest {
    ownerName: string;
    initialBalance?: string | null;
}

export interface UpdateAccountRequest {
    ownerName?: string | null;
    status?: AccountStatus | null;
}

export interface AccountsQuery {
    page?: number;
    size?: number;
    sort?: string;
    status?: AccountStatus;
    ownerName?: string;
}
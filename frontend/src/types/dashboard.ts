import type { TransactionType } from './transaction';

export interface DashboardQuery {
    accountId?: string;
    createdFrom?: string;
    createdTo?: string;
}

export interface BalancePoint {
    date: string;
    balance: string;
}

export interface TypeSpending {
    type: TransactionType;
    total: string;
    count: number;
}

export interface DashboardSummary {
    balanceOverTime: BalancePoint[];
    spendByType: TypeSpending[];
}

import client from './client';
import type { Transaction, TransactionsQuery } from '../types/transaction';
import type { PagedResponse } from '../types/pagination';

const BASE = '/transactions';

export const transactionsApi = {
    getById: (id: string): Promise<Transaction> =>
        client.get<Transaction>(`${BASE}/${id}`).then((r) => r.data),

    getAll: (query: TransactionsQuery = {}): Promise<PagedResponse<Transaction>> =>
        client.get<PagedResponse<Transaction>>(BASE, { params: query }).then((r) => r.data),
};

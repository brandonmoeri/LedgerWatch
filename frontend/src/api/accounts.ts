import client from './client';
import type { Account, AccountsQuery, CreateAccountRequest, UpdateAccountRequest } from '../types/account';
import type { PagedResponse } from '../types/pagination';

const BASE = '/accounts';

export const accountsApi = {
    getById: (id: string): Promise<Account> =>
        client.get<Account>(`${BASE}/${id}`).then((r) => r.data),

    getAll: (query: AccountsQuery = {}): Promise<PagedResponse<Account>> =>
        client.get<PagedResponse<Account>>(BASE, { params: query }).then((r) => r.data),

    create: (body: CreateAccountRequest): Promise<Account> =>
        client.post<Account>(BASE, body).then((r) => r.data),

    update: (id: string, body: UpdateAccountRequest): Promise<Account> =>
        client.patch<Account>(`${BASE}/${id}`, body).then((r) => r.data),
};

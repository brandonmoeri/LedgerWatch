import client from './client';
import type { Account, CreateAccountRequest, UpdateAccountRequest } from '../types/account';

const BASE = '/accounts';

export const accountsApi = {
    getById: (id: string): Promise<Account> =>
        client.get<Account>(`${BASE}/${id}`).then((r) => r.data),

    getAll: (): Promise<Account[]> =>
        client.get<Account[]>(BASE).then((r) => r.data),

    create: (body: CreateAccountRequest): Promise<Account> =>
        client.post<Account>(BASE, body).then((r) => r.data),

    update: (id: string, body: UpdateAccountRequest): Promise<Account> =>
        client.patch<Account>(`${BASE}/${id}`, body).then((r) => r.data),
};
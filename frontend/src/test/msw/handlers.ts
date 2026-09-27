import { http, HttpResponse, delay } from 'msw';
import type { Account } from '../../types/account';
import type { Transaction } from '../../types/transaction';
import type { DashboardSummary } from '../../types/dashboard';
import type { PagedResponse } from '../../types/pagination';
import type { LoginResponse } from '../../api/auth';

function emptyPage<T>(): PagedResponse<T> {
    return { content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } };
}

export const handlers = [
    http.get('/api/accounts', () => HttpResponse.json(emptyPage<Account>())),

    // Never resolves by default, matching the "still loading" case tests rely on.
    // Override with server.use(...) for tests that need a resolved account.
    http.get('/api/accounts/:id', async () => {
        await delay('infinite');
    }),

    http.post('/api/accounts', async ({ request }) => {
        const body = (await request.json()) as { ownerName: string; initialBalance?: string | null };
        const now = new Date().toISOString();
        const account: Account = {
            id: 'acc-new',
            ownerName: body.ownerName,
            status: 'ACTIVE',
            balance: body.initialBalance ?? '0.00',
            createdAt: now,
            updatedAt: now,
        };
        return HttpResponse.json(account, { status: 201 });
    }),

    http.patch('/api/accounts/:id', async ({ request, params }) => {
        const body = (await request.json()) as { ownerName?: string | null; status?: Account['status'] | null };
        const now = new Date().toISOString();
        const account: Account = {
            id: String(params.id),
            ownerName: body.ownerName ?? 'Unknown',
            status: body.status ?? 'ACTIVE',
            balance: '0.00',
            createdAt: now,
            updatedAt: now,
        };
        return HttpResponse.json(account);
    }),

    http.get('/api/transactions', () => HttpResponse.json(emptyPage<Transaction>())),

    http.get('/api/transactions/:id', async () => {
        await delay('infinite');
    }),

    http.get('/api/transactions/summary', () =>
        HttpResponse.json({ balanceOverTime: [], spendByType: [] } satisfies DashboardSummary)
    ),

    http.post('/api/auth/login', () =>
        HttpResponse.json({
            token: 'test-token',
            tokenType: 'Bearer',
            expiresIn: 3600,
        } satisfies LoginResponse)
    ),
];

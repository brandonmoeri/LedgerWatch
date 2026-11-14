import { describe, it, expect } from 'vitest';
import reducer, { fetchAccountFlags } from './accountFlagsSlice';
import type { AccountFlagsState } from './accountFlagsSlice';
import type { FraudFlag } from '../types/fraud';
import type { PagedResponse } from '../types/pagination';

const flag: FraudFlag = {
    id: 'flag-1',
    transactionId: 'tx-1',
    accountId: 'acc-1',
    rule: 'VELOCITY',
    detail: '4 transactions in PT10M (max 3)',
    blocked: true,
    flaggedAt: '2026-11-01T12:00:00Z',
};

const page = (items: FraudFlag[]): PagedResponse<FraudFlag> => ({
    content: items,
    page: { number: 1, size: 20, totalElements: 21, totalPages: 2 },
});

const loaded: AccountFlagsState = {
    accountId: 'acc-1',
    items: [flag],
    page: 1,
    totalPages: 2,
    status: 'succeeded',
    error: null,
    requestId: 'r1',
};

describe('accountFlagsSlice', () => {
    it('starts idle with no account', () => {
        expect(reducer(undefined, { type: 'unknown' })).toMatchObject({
            accountId: null,
            items: [],
            status: 'idle',
        });
    });

    it('keeps the current flags while loading another page of the same account', () => {
        const state = reducer(loaded, fetchAccountFlags.pending('r2', { accountId: 'acc-1', page: 0 }));
        expect(state).toMatchObject({ status: 'loading', items: [flag], requestId: 'r2' });
    });

    it('clears the previous account\'s flags when loading another account', () => {
        const state = reducer(loaded, fetchAccountFlags.pending('r2', { accountId: 'acc-2' }));
        expect(state).toMatchObject({ accountId: 'acc-2', items: [], page: 0, totalPages: 0, status: 'loading' });
    });

    it('stores the page on fulfilled', () => {
        let state = reducer(undefined, fetchAccountFlags.pending('r1', { accountId: 'acc-1' }));
        state = reducer(state, fetchAccountFlags.fulfilled(page([flag]), 'r1', { accountId: 'acc-1' }));
        expect(state).toMatchObject({ status: 'succeeded', items: [flag], page: 1, totalPages: 2 });
    });

    it('drops an answer to a superseded request', () => {
        let state = reducer(undefined, fetchAccountFlags.pending('r1', { accountId: 'acc-1' }));
        state = reducer(state, fetchAccountFlags.pending('r2', { accountId: 'acc-2' }));
        state = reducer(state, fetchAccountFlags.fulfilled(page([flag]), 'r1', { accountId: 'acc-1' }));
        state = reducer(state, fetchAccountFlags.rejected(new Error('late'), 'r1', { accountId: 'acc-1' }));
        expect(state).toMatchObject({ accountId: 'acc-2', items: [], status: 'loading', error: null });
    });

    it('records the error on rejected', () => {
        let state = reducer(undefined, fetchAccountFlags.pending('r1', { accountId: 'acc-1' }));
        state = reducer(state, fetchAccountFlags.rejected(new Error('boom'), 'r1', { accountId: 'acc-1' }));
        expect(state).toMatchObject({ status: 'failed', error: 'boom' });
    });
});

import { describe, it, expect } from 'vitest';
import reducer, { fetchAccounts, fetchAccountById, updateAccount } from './accountsSlice';
import type { Account } from '../types/account';
import type { PagedResponse } from '../types/pagination';

type SliceStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

interface AccountsState {
    items: Account[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    selectedAccount: Account | null;
    selectedStatus: SliceStatus;
    status: SliceStatus;
    error: string | null;
}

const initialState: AccountsState = {
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    selectedAccount: null,
    selectedStatus: 'idle',
    status: 'idle',
    error: null,
};

const mockAccount: Account = {
    id: 'acc-1',
    ownerName: 'Alice Smith',
    status: 'ACTIVE',
    balance: '1000.00',
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
};

const pagedResponse = (items: Account[]): PagedResponse<Account> => ({
    content: items,
    page: {
        number: 1,
        size: 20,
        totalElements: items.length,
        totalPages: 2,
    },
});

describe('accountsSlice', () => {
    it('returns the initial state', () => {
        expect(reducer(undefined, { type: 'unknown' })).toEqual(initialState);
    });

    describe('fetchAccounts', () => {
        it('sets status to loading and clears error on pending', () => {
            const state = reducer(
                { ...initialState, status: 'failed', error: 'boom' },
                fetchAccounts.pending('requestId', {})
            );
            expect(state.status).toBe('loading');
            expect(state.error).toBeNull();
        });

        it('stores items and pagination metadata on fulfilled', () => {
            const response = pagedResponse([mockAccount]);
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchAccounts.fulfilled(response, 'requestId', {})
            );
            expect(state.status).toBe('succeeded');
            expect(state.items).toEqual([mockAccount]);
            expect(state.page).toBe(response.page.number);
            expect(state.size).toBe(response.page.size);
            expect(state.totalElements).toBe(response.page.totalElements);
            expect(state.totalPages).toBe(response.page.totalPages);
        });

        it('sets status to failed and records the error message on rejected', () => {
            const error = new Error('Network Error');
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchAccounts.rejected(error, 'requestId', {})
            );
            expect(state.status).toBe('failed');
            expect(state.error).toBe('Network Error');
        });

        it('falls back to a default error message when none is provided', () => {
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchAccounts.rejected({} as Error, 'requestId', {})
            );
            expect(state.error).toBe('Failed to load accounts');
        });
    });

    describe('fetchAccountById', () => {
        it('sets selectedStatus to loading and clears error on pending', () => {
            const state = reducer(
                { ...initialState, error: 'stale error' },
                fetchAccountById.pending('requestId', 'acc-1')
            );
            expect(state.selectedStatus).toBe('loading');
            expect(state.error).toBeNull();
        });

        it('stores the selected account on fulfilled', () => {
            const state = reducer(
                { ...initialState, selectedStatus: 'loading' },
                fetchAccountById.fulfilled(mockAccount, 'requestId', 'acc-1')
            );
            expect(state.selectedStatus).toBe('succeeded');
            expect(state.selectedAccount).toEqual(mockAccount);
        });

        it('sets selectedStatus to failed and records the error message on rejected', () => {
            const error = new Error('Not Found');
            const state = reducer(
                { ...initialState, selectedStatus: 'loading' },
                fetchAccountById.rejected(error, 'requestId', 'acc-1')
            );
            expect(state.selectedStatus).toBe('failed');
            expect(state.error).toBe('Not Found');
        });

        it('falls back to a default error message when none is provided', () => {
            const state = reducer(
                { ...initialState, selectedStatus: 'loading' },
                fetchAccountById.rejected({} as Error, 'requestId', 'acc-1')
            );
            expect(state.error).toBe('Failed to load account');
        });
    });

    describe('updateAccount.fulfilled', () => {
        const arg = { id: 'acc-1', body: { ownerName: 'Alice Updated' } };

        it('sets the selected account to the updated payload', () => {
            const updated: Account = { ...mockAccount, ownerName: 'Alice Updated' };
            const state = reducer(
                { ...initialState, selectedAccount: mockAccount },
                updateAccount.fulfilled(updated, 'requestId', arg)
            );
            expect(state.selectedAccount).toEqual(updated);
        });

        it('replaces the matching account in items when found', () => {
            const other: Account = { ...mockAccount, id: 'acc-2', ownerName: 'Bob Jones' };
            const updated: Account = { ...mockAccount, ownerName: 'Alice Updated' };
            const state = reducer(
                { ...initialState, items: [mockAccount, other] },
                updateAccount.fulfilled(updated, 'requestId', arg)
            );
            expect(state.items).toEqual([updated, other]);
        });

        it('leaves items unchanged when the updated account is not present in the list', () => {
            const other: Account = { ...mockAccount, id: 'acc-2', ownerName: 'Bob Jones' };
            const updatedElsewhere: Account = { ...mockAccount, id: 'acc-999', ownerName: 'Ghost' };
            const state = reducer(
                { ...initialState, items: [other] },
                updateAccount.fulfilled(updatedElsewhere, 'requestId', { id: 'acc-999', body: {} })
            );
            expect(state.items).toEqual([other]);
        });

        it('does not mutate items when the items list is empty', () => {
            const updated: Account = { ...mockAccount, ownerName: 'Alice Updated' };
            const state = reducer(
                { ...initialState, items: [] },
                updateAccount.fulfilled(updated, 'requestId', arg)
            );
            expect(state.items).toEqual([]);
        });
    });
});

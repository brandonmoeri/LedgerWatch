import { describe, it, expect } from 'vitest';
import reducer, { fetchTransactions } from './transactionsSlice';
import type { Transaction } from '../types/transaction';
import type { PagedResponse } from '../types/pagination';

type SliceStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

interface TransactionsState {
    items: Transaction[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    status: SliceStatus;
    error: string | null;
}

const initialState: TransactionsState = {
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    status: 'idle',
    error: null,
};

const mockTransaction: Transaction = {
    id: 'txn-1',
    accountId: 'acc-1',
    type: 'DEBIT',
    status: 'POSTED',
    amount: '50.00',
    description: 'Coffee',
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
};

const pagedResponse = (items: Transaction[]): PagedResponse<Transaction> => ({
    content: items,
    page: {
        number: 1,
        size: 20,
        totalElements: items.length,
        totalPages: 2,
    },
});

describe('transactionsSlice', () => {
    it('returns the initial state', () => {
        expect(reducer(undefined, { type: 'unknown' })).toEqual(initialState);
    });

    describe('fetchTransactions', () => {
        it('sets status to loading and clears error on pending', () => {
            const state = reducer(
                { ...initialState, status: 'failed', error: 'boom' },
                fetchTransactions.pending('requestId', {})
            );
            expect(state.status).toBe('loading');
            expect(state.error).toBeNull();
        });

        it('stores items and pagination metadata on fulfilled', () => {
            const response = pagedResponse([mockTransaction]);
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchTransactions.fulfilled(response, 'requestId', {})
            );
            expect(state.status).toBe('succeeded');
            expect(state.items).toEqual([mockTransaction]);
            expect(state.page).toBe(response.page.number);
            expect(state.size).toBe(response.page.size);
            expect(state.totalElements).toBe(response.page.totalElements);
            expect(state.totalPages).toBe(response.page.totalPages);
        });

        it('sets status to failed and records the error message on rejected', () => {
            const error = new Error('Network Error');
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchTransactions.rejected(error, 'requestId', {})
            );
            expect(state.status).toBe('failed');
            expect(state.error).toBe('Network Error');
        });

        it('falls back to a default error message when none is provided', () => {
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchTransactions.rejected({} as Error, 'requestId', {})
            );
            expect(state.error).toBe('Failed to load transactions');
        });
    });
});

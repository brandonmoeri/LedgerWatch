import { describe, it, expect } from 'vitest';
import reducer, { fetchDashboardSummary } from './dashboardSlice';
import type { DashboardSummary } from '../types/dashboard';

type SliceStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

interface DashboardState {
    summary: DashboardSummary | null;
    status: SliceStatus;
    error: string | null;
}

const initialState: DashboardState = {
    summary: null,
    status: 'idle',
    error: null,
};

const mockSummary: DashboardSummary = {
    balanceOverTime: [{ date: '2024-01-15', balance: '1000.00' }],
    spendByType: [{ type: 'DEBIT', total: '50.00', count: 3 }],
};

describe('dashboardSlice', () => {
    it('returns the initial state', () => {
        expect(reducer(undefined, { type: 'unknown' })).toEqual(initialState);
    });

    describe('fetchDashboardSummary', () => {
        it('sets status to loading and clears error on pending', () => {
            const state = reducer(
                { ...initialState, status: 'failed', error: 'boom' },
                fetchDashboardSummary.pending('requestId', {})
            );
            expect(state.status).toBe('loading');
            expect(state.error).toBeNull();
        });

        it('stores the summary on fulfilled', () => {
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchDashboardSummary.fulfilled(mockSummary, 'requestId', {})
            );
            expect(state.status).toBe('succeeded');
            expect(state.summary).toEqual(mockSummary);
        });

        it('sets status to failed and records the error message on rejected', () => {
            const error = new Error('Network Error');
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchDashboardSummary.rejected(error, 'requestId', {})
            );
            expect(state.status).toBe('failed');
            expect(state.error).toBe('Network Error');
        });

        it('falls back to a default error message when none is provided', () => {
            const state = reducer(
                { ...initialState, status: 'loading' },
                fetchDashboardSummary.rejected({} as Error, 'requestId', {})
            );
            expect(state.error).toBe('Failed to load dashboard summary');
        });
    });
});

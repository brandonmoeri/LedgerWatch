import { render, screen, act } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import { configureStore } from '@reduxjs/toolkit';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import TransactionsPage from './TransactionsPage';
import transactionsReducer from '../store/transactionsSlice';
import { transactionsApi } from '../api/transactions';
import type { Transaction } from '../types/transaction';

vi.mock('../api/transactions', () => ({
    transactionsApi: {
        getAll: vi.fn(),
        getById: vi.fn(),
    },
}));

const mockTransaction: Transaction = {
    id: 'tx-1',
    accountId: 'acc-1',
    type: 'CREDIT',
    status: 'POSTED',
    amount: '100.00',
    description: 'Paycheck',
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
};

type SliceStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

function renderPage(overrides: {
    items?: Transaction[];
    status?: SliceStatus;
    error?: string | null;
    page?: number;
    totalPages?: number;
} = {}) {
    const items = overrides.items ?? [];
    const store = configureStore({
        reducer: { transactions: transactionsReducer },
        preloadedState: {
            transactions: {
                items,
                page: overrides.page ?? 0,
                size: 20,
                totalElements: items.length,
                totalPages: overrides.totalPages ?? 1,
                status: (overrides.status ?? 'succeeded') as SliceStatus,
                error: overrides.error ?? null,
            },
        },
    });
    render(
        <Provider store={store}>
            <MemoryRouter>
                <TransactionsPage />
            </MemoryRouter>
        </Provider>
    );
}

describe('TransactionsPage', () => {
    describe('chrome always present', () => {
        it('renders the page heading', () => {
            renderPage();
            expect(screen.getByRole('heading', { name: /transactions/i })).toBeInTheDocument();
        });

        it('renders type, status, and date range filter controls', () => {
            renderPage();
            expect(screen.getByLabelText(/^type$/i)).toBeInTheDocument();
            expect(screen.getByLabelText(/^status$/i)).toBeInTheDocument();
            expect(screen.getByLabelText(/^from$/i)).toBeInTheDocument();
            expect(screen.getByLabelText(/^to$/i)).toBeInTheDocument();
        });
    });

    describe('loading state', () => {
        it('shows the loading indicator', () => {
            renderPage({ status: 'loading' });
            expect(screen.getByText(/loading/i)).toBeInTheDocument();
        });

        it('does not render the transactions table', () => {
            renderPage({ status: 'loading' });
            expect(screen.queryByRole('table')).not.toBeInTheDocument();
        });
    });

    describe('error state', () => {
        it('shows the error message', () => {
            renderPage({ status: 'failed', error: 'Network Error' });
            expect(screen.getByText(/error: network error/i)).toBeInTheDocument();
        });

        it('does not render the transactions table', () => {
            renderPage({ status: 'failed', error: 'Network Error' });
            expect(screen.queryByRole('table')).not.toBeInTheDocument();
        });

        it('offers a retry action', () => {
            renderPage({ status: 'failed', error: 'Network Error' });
            expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
        });
    });

    describe('empty state', () => {
        it('shows an empty state message instead of the table', () => {
            renderPage({ status: 'succeeded', items: [] });
            expect(screen.getByText(/no transactions found/i)).toBeInTheDocument();
            expect(screen.queryByRole('table')).not.toBeInTheDocument();
        });
    });

    describe('populated state', () => {
        it('renders one data row per transaction', () => {
            renderPage({ status: 'succeeded', items: [mockTransaction] });
            expect(screen.getAllByRole('row')).toHaveLength(2);
        });

        it('displays type, status, amount, and description', () => {
            renderPage({ status: 'succeeded', items: [mockTransaction] });
            expect(screen.getByText('CREDIT')).toBeInTheDocument();
            expect(screen.getByText('POSTED')).toBeInTheDocument();
            expect(screen.getByText('100.00')).toBeInTheDocument();
            expect(screen.getByText('Paycheck')).toBeInTheDocument();
        });
    });

    describe('idle state', () => {
        beforeEach(() => {
            vi.mocked(transactionsApi.getAll).mockResolvedValue({
                content: [],
                page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
            });
        });

        it('dispatches fetchTransactions when status is idle', async () => {
            await act(async () => renderPage({ status: 'idle' }));
            expect(vi.mocked(transactionsApi.getAll)).toHaveBeenCalledTimes(1);
        });
    });

    describe('pagination controls', () => {
        it('disables Previous on the first page and enables Next when more pages exist', () => {
            renderPage({ items: [mockTransaction], page: 0, totalPages: 3 });
            expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
            expect(screen.getByRole('button', { name: /^next$/i })).toBeEnabled();
        });

        it('enables Previous and disables Next on the last page', () => {
            renderPage({ items: [mockTransaction], page: 2, totalPages: 3 });
            expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled();
            expect(screen.getByRole('button', { name: /^next$/i })).toBeDisabled();
        });

        it('shows the current page and total pages', () => {
            renderPage({ items: [mockTransaction], page: 1, totalPages: 3 });
            expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
        });
    });
});

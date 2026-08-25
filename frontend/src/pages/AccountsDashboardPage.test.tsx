import { render, screen, act } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import { configureStore } from '@reduxjs/toolkit';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import AccountsDashboardPage from './AccountsDashboardPage';
import accountsReducer from '../store/accountsSlice';
import { accountsApi } from '../api/accounts';
import type { Account } from '../types/account';

vi.mock('../api/accounts', () => ({
    accountsApi: {
        getAll: vi.fn(),
        getById: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
    },
}));

const mockAccount: Account = {
    id: 'acc-1',
    ownerName: 'Alice Smith',
    status: 'ACTIVE',
    balance: '1000.00',
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
};

type SliceStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

function renderDashboard(overrides: {
    items?: Account[];
    status?: SliceStatus;
    error?: string | null;
    page?: number;
    totalPages?: number;
} = {}) {
    const items = overrides.items ?? [];
    const store = configureStore({
        reducer: { accounts: accountsReducer },
        preloadedState: {
            accounts: {
                items,
                page: overrides.page ?? 0,
                size: 20,
                totalElements: items.length,
                totalPages: overrides.totalPages ?? 1,
                selectedAccount: null,
                selectedStatus: 'idle' as const,
                status: (overrides.status ?? 'succeeded') as SliceStatus,
                error: overrides.error ?? null,
            },
        },
    });
    render(
        <Provider store={store}>
            <MemoryRouter>
                <AccountsDashboardPage />
            </MemoryRouter>
        </Provider>
    );
}

describe('AccountsDashboardPage', () => {
    describe('chrome always present', () => {
        it('renders the page heading', () => {
            renderDashboard();
            expect(screen.getByRole('heading', { name: /accounts dashboard/i })).toBeInTheDocument();
        });

        it('renders the New Account Link', () => {
            renderDashboard();
            expect(screen.getByRole('link', { name: /new account/i })).toBeInTheDocument();
        });
    });

    describe('loading state', () => {
        it('shows the loading indicator', () => {
            renderDashboard({ status: 'loading' });
            expect(screen.getByText(/loading/i)).toBeInTheDocument();
        });

        it('does not render the accounts table', () => {
            renderDashboard({ status: 'loading' });
            expect(screen.queryByRole('table')).not.toBeInTheDocument();
        })
    });

    describe('error state', () => {
        it('shows the error message', () => {
            renderDashboard({ status: 'failed', error: 'Network Error' });
            expect(screen.getByText(/error: network error/i)).toBeInTheDocument();
        })

        it('does not render the accounts table', () => {
            renderDashboard({ status: 'failed', error: 'Network Error' });
            expect(screen.queryByRole('table')).not.toBeInTheDocument();
        })
    });

    describe('empty state', () => {
        it('renders the table with only the header row', () => {
            renderDashboard({ status: 'succeeded', items: [] });
            expect(screen.getByRole('table')).toBeInTheDocument();
            // Only the thead <tr> is present; no data rows
            expect(screen.getAllByRole('row')).toHaveLength(1);
        });

        it('does not show a loading or error message', () => {
            renderDashboard({ status: 'succeeded', items: [] });
            expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
            expect(screen.queryByText(/^error:/i)).not.toBeInTheDocument();
        });
    });

    describe('populated state', () => {
        it('renders one data row per account', () => {
            renderDashboard({ status: 'succeeded', items: [mockAccount] });
            // header row + 1 data row
            expect(screen.getAllByRole('row')).toHaveLength(2);
        });

        it('displays owner name, status, and balance', () => {
            renderDashboard({ status: 'succeeded', items: [mockAccount] });
            expect(screen.getByText('Alice Smith')).toBeInTheDocument();
            expect(screen.getByText('ACTIVE')).toBeInTheDocument();
            expect(screen.getByText('1000.00')).toBeInTheDocument();
        });

        it('renders a View link pointing to the account detail route', () => {
            renderDashboard({ status: 'succeeded', items: [mockAccount] });
            expect(screen.getByRole('link', { name: /^view$/i }))
                .toHaveAttribute('href', '/accounts/acc-1');
        });

        it('renders one row per account when multiple are present', () => {
            const second: Account = { ...mockAccount, id: 'acc-2', ownerName: 'Bob Jones' };
            renderDashboard({ items: [mockAccount, second] });
            // header row + 2 data rows
            expect(screen.getAllByRole('row')).toHaveLength(3);
            expect(screen.getByText('Alice Smith')).toBeInTheDocument();
            expect(screen.getByText('Bob Jones')).toBeInTheDocument();
        });

        it('displays createdAt formatted for the locale', () => {
            renderDashboard({ items: [mockAccount] });
            const expected = new Date(mockAccount.createdAt).toLocaleDateString();
            expect(screen.getByText(expected)).toBeInTheDocument();
        });
    });

    describe('idle state', () => {
        beforeEach(() => {
            vi.mocked(accountsApi.getAll).mockResolvedValue({
                content: [],
                page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
            });
        });

        it('dispatches fetchAccounts when status is idle', async () => {
            await act(async () => renderDashboard({ status: 'idle' }));
            expect(vi.mocked(accountsApi.getAll)).toHaveBeenCalledTimes(1);
        });
    });

    describe('pagination controls', () => {
        it('disables Previous on the first page and enables Next when more pages exist', () => {
            renderDashboard({ items: [mockAccount], page: 0, totalPages: 3 });
            expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
            expect(screen.getByRole('button', { name: /^next$/i })).toBeEnabled();
        });

        it('enables Previous and disables Next on the last page', () => {
            renderDashboard({ items: [mockAccount], page: 2, totalPages: 3 });
            expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled();
            expect(screen.getByRole('button', { name: /^next$/i })).toBeDisabled();
        });

        it('shows the current page and total pages', () => {
            renderDashboard({ items: [mockAccount], page: 1, totalPages: 3 });
            expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
        });
    });
})
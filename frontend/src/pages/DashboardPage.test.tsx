import { render, screen, act } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import { configureStore } from '@reduxjs/toolkit';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import DashboardPage from './DashboardPage';
import dashboardReducer from '../store/dashboardSlice';
import { dashboardApi } from '../api/dashboard';
import type { DashboardSummary } from '../types/dashboard';

vi.mock('../api/dashboard', () => ({
    dashboardApi: {
        getSummary: vi.fn(),
    },
}));

const mockSummary: DashboardSummary = {
    balanceOverTime: [
        { date: '2026-01-01', balance: '100.00' },
        { date: '2026-01-02', balance: '70.00' },
    ],
    spendByType: [
        { type: 'CREDIT', total: '100.00', count: 1 },
        { type: 'DEBIT', total: '30.00', count: 1 },
    ],
};

type SliceStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

function renderPage(overrides: { summary?: DashboardSummary | null; status?: SliceStatus; error?: string | null } = {}) {
    const store = configureStore({
        reducer: { dashboard: dashboardReducer },
        preloadedState: {
            dashboard: {
                summary: overrides.summary ?? null,
                status: (overrides.status ?? 'succeeded') as SliceStatus,
                error: overrides.error ?? null,
            },
        },
    });
    render(
        <Provider store={store}>
            <MemoryRouter>
                <DashboardPage />
            </MemoryRouter>
        </Provider>
    );
}

describe('DashboardPage', () => {
    describe('chrome always present', () => {
        it('renders the page heading', () => {
            renderPage();
            expect(screen.getByRole('heading', { name: /dashboard/i })).toBeInTheDocument();
        });

        it('renders account and date range filter controls', () => {
            renderPage();
            expect(screen.getByLabelText(/account id/i)).toBeInTheDocument();
            expect(screen.getByLabelText(/^from$/i)).toBeInTheDocument();
            expect(screen.getByLabelText(/^to$/i)).toBeInTheDocument();
        });
    });

    describe('loading state', () => {
        it('shows the loading indicator', () => {
            renderPage({ status: 'loading' });
            expect(screen.getByText(/loading/i)).toBeInTheDocument();
        });
    });

    describe('error state', () => {
        it('shows the error message and a retry action', () => {
            renderPage({ status: 'failed', error: 'Network Error' });
            expect(screen.getByText(/error: network error/i)).toBeInTheDocument();
            expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
        });
    });

    describe('empty state', () => {
        it('shows an empty state message for both charts', () => {
            renderPage({ status: 'succeeded', summary: { balanceOverTime: [], spendByType: [] } });
            expect(screen.getAllByText(/no posted transactions in this range/i)).toHaveLength(2);
        });
    });

    describe('populated state', () => {
        it('renders both chart cards', () => {
            renderPage({ status: 'succeeded', summary: mockSummary });
            expect(screen.getByRole('heading', { name: /balance over time/i })).toBeInTheDocument();
            expect(screen.getByRole('heading', { name: /spend by transaction type/i })).toBeInTheDocument();
        });
    });

    describe('idle state', () => {
        beforeEach(() => {
            vi.mocked(dashboardApi.getSummary).mockResolvedValue({ balanceOverTime: [], spendByType: [] });
        });

        it('dispatches fetchDashboardSummary when status is idle', async () => {
            await act(async () => renderPage({ status: 'idle' }));
            expect(vi.mocked(dashboardApi.getSummary)).toHaveBeenCalledTimes(1);
        });
    });
});

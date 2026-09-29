import { render, screen, act, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { vi, describe, it, expect, afterEach } from 'vitest';
import CreateAccountPage from './CreateAccountPage';
import { accountsApi } from '../api/accounts';
import type { Account } from '../types/account';
import type { ApiError } from '../api/client';

const mockAccount: Account = {
    id: 'acc-1',
    ownerName: 'Alice Smith',
    status: 'ACTIVE',
    balance: '1000.00',
    createdAt: '2024-01-15T10:00:00Z',
    updatedAt: '2024-01-15T10:00:00Z',
};

function renderPage() {
    render(
        <MemoryRouter initialEntries={['/accounts/new']}>
            <Routes>
                <Route path="/accounts/new" element={<CreateAccountPage />} />
                <Route path="/accounts/:id" element={<div>Account Detail Page</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe('CreateAccountPage', () => {
    afterEach(() => {
        vi.restoreAllMocks();
    });

    describe('form chrome', () => {
        it('renders the page heading', () => {
            renderPage();
            expect(screen.getByRole('heading', { name: /create account/i })).toBeInTheDocument();
        });

        it('renders owner name and initial balance inputs and a submit button', () => {
            renderPage();
            expect(screen.getByPlaceholderText(/owner name/i)).toBeInTheDocument();
            expect(screen.getByPlaceholderText(/initial balance/i)).toBeInTheDocument();
            expect(screen.getByRole('button', { name: /create/i })).toBeInTheDocument();
        });

        it('does not show an error message before submission', () => {
            renderPage();
            expect(screen.queryByRole('list')).not.toBeInTheDocument();
        });
    });

    describe('successful submission', () => {
        it('submits the entered owner name and initial balance', async () => {
            const createSpy = vi.spyOn(accountsApi, 'create').mockResolvedValue(mockAccount);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'Alice Smith' } });
            fireEvent.change(screen.getByPlaceholderText(/initial balance/i), { target: { value: '500.00' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(createSpy).toHaveBeenCalledWith({ ownerName: 'Alice Smith', initialBalance: '500.00' });
        });

        it('sends null for initial balance when left blank', async () => {
            const createSpy = vi.spyOn(accountsApi, 'create').mockResolvedValue(mockAccount);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'Alice Smith' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(createSpy).toHaveBeenCalledWith({ ownerName: 'Alice Smith', initialBalance: null });
        });

        it('navigates to the new account detail page on success', async () => {
            vi.spyOn(accountsApi, 'create').mockResolvedValue(mockAccount);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'Alice Smith' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(screen.getByText('Account Detail Page')).toBeInTheDocument();
        });
    });

    describe('failed submission', () => {
        it('shows the error detail message and does not navigate away', async () => {
            const apiError: ApiError = { status: 400, detail: 'Validation failed' };
            vi.spyOn(accountsApi, 'create').mockRejectedValue(apiError);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'Alice Smith' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(screen.getByText('Validation failed')).toBeInTheDocument();
            expect(screen.queryByText('Account Detail Page')).not.toBeInTheDocument();
        });

        it('lists field-level validation errors returned by the API', async () => {
            const apiError: ApiError = {
                status: 400,
                detail: 'Validation failed',
                errors: { ownerName: 'must not be blank', initialBalance: 'must be a positive number' },
            };
            vi.spyOn(accountsApi, 'create').mockRejectedValue(apiError);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'x' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(screen.getByText(/ownerName: must not be blank/)).toBeInTheDocument();
            expect(screen.getByText(/initialBalance: must be a positive number/)).toBeInTheDocument();
        });

        it('does not render a field-error list when the API error has no field errors', async () => {
            const apiError: ApiError = { status: 500, detail: 'Internal server error' };
            vi.spyOn(accountsApi, 'create').mockRejectedValue(apiError);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'Alice Smith' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(screen.getByText('Internal server error')).toBeInTheDocument();
            expect(screen.queryByRole('list')).not.toBeInTheDocument();
        });

        it('clears a previous error once a corrected submission succeeds', async () => {
            const apiError: ApiError = { status: 400, detail: 'Validation failed' };
            const createSpy = vi.spyOn(accountsApi, 'create')
                .mockRejectedValueOnce(apiError)
                .mockResolvedValueOnce(mockAccount);
            renderPage();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'A' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });
            expect(screen.getByText('Validation failed')).toBeInTheDocument();

            fireEvent.change(screen.getByPlaceholderText(/owner name/i), { target: { value: 'Alice Smith' } });
            await act(async () => {
                fireEvent.click(screen.getByRole('button', { name: /create/i }));
            });

            expect(createSpy).toHaveBeenCalledTimes(2);
            expect(screen.queryByText('Validation failed')).not.toBeInTheDocument();
            expect(screen.getByText('Account Detail Page')).toBeInTheDocument();
        });
    });
});

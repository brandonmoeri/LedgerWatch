import { render, screen } from '@testing-library/react';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import App from './App';
import AccountDetailPage from './pages/AccountDetailPage';
import CreateAccountPage from './pages/CreateAccountPage';
import accountsReducer from './store/accountsSlice';
import { auth } from './auth/auth';

// Prevent real HTTP calls from either page
vi.mock('./api/accounts', () => ({
    accountsApi: {
        getAll: vi.fn(() => Promise.resolve([])),
        create: vi.fn(),
        getById: vi.fn(() => new Promise(() => {})),
        update: vi.fn(),
    },
}));

function makeToken(exp: number): string {
    const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const payload = btoa(JSON.stringify({ sub: 'test-user', roles: ['ADMIN'], exp }));
    return `${header}.${payload}.test-signature`;
}

describe('App routing smoke tests', () => {
    beforeEach(() => {
        auth.setToken(makeToken(Math.floor(Date.now() / 1_000) + 3600));
    });

    afterEach(() => {
        auth.clearToken();
    });

    it('redirects "/" to "/accounts" and renders the Accounts Dashboard', async() => {
        // jsdom starts at window.location = "/" which triggers the <Navigate> redirect
        render(<App />);

        expect(
            await screen.findByRole('heading', { name: /accounts dashboard/i })
        ).toBeInTheDocument();
    });

    it('renders the Account Detail loading state for "/accounts/:id"', () => {
        const store = configureStore({ reducer: { accounts: accountsReducer }});
        const router = createMemoryRouter(
            [
                { path: '/accounts/new', element: <CreateAccountPage /> },
                { path: '/accounts/:id', element: <AccountDetailPage /> },
            ],
            { initialEntries: ['/accounts/abc-123'] }
        );

        render(
            <Provider store={store}>
                <RouterProvider router={router} />
            </Provider>
        );

        expect(screen.getByText(/loading/i)).toBeInTheDocument();
    });
});
import { render, screen } from '@testing-library/react';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { vi, describe, it, expect } from 'vitest';
import App from './App';
import AccountDetailPage from './pages/AccountDetailPage';
import CreateAccountPage from './pages/CreateAccountPage';
import accountsReducer from './store/accountsSlice';

// Prevent real HTTP calls from either page
vi.mock('./api/accounts', () => ({
    accountsApi: {
        getAll: vi.fn(() => Promise.resolve([])),
        create: vi.fn(),
        getById: vi.fn(() => new Promise(() => {})),
        update: vi.fn(),
    },
}));

describe('App routing smoke tests', () => {
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
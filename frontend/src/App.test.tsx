import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { vi, describe, it, expect } from 'vitest';
import App from './App';
import AccountDetailPage from './pages/AccountDetailPage';
import CreateAccountPage from './pages/CreateAccountPage';

// Prevent real HTTP calls from either page
vi.mock('./api/accounts', () => ({
    accountsApi: {
        create: vi.fn(),
        getById: vi.fn(() => new Promise(() => {})),
        update: vi.fn(),
    },
}));

describe('App routing smoke tests', () => {
    it('redirects "/" to "/accounts/new" and renders the Create Account form', async() => {
        // jsdom starts at window.location = "/" which triggers the <Navigate> redirect
        render(<App />);

        expect(
            await screen.findByRole('heading', { name: /create account/i })
        ).toBeInTheDocument();
    });

    it('renders the Account Detail loading state for "/accounts/:id"', () => {
        const router = createMemoryRouter(
            [
                { path: '/accounts/new', element: <CreateAccountPage /> },
                { path: '/accounts/:id', element: <AccountDetailPage /> },
            ],
            { initialEntries: ['/accounts/abc-123'] }
        );

        render( <RouterProvider router={router} />);

        expect(screen.getByText(/loading/i)).toBeInTheDocument();
    });
});
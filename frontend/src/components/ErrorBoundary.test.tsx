import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi, describe, it, expect, afterEach } from 'vitest';
import ErrorBoundary from './ErrorBoundary';

function Bomb({ shouldThrow }: { shouldThrow: boolean }) {
    if (shouldThrow) {
        throw new Error('boom');
    }
    return <p>Recovered</p>;
}

describe('ErrorBoundary', () => {
    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('renders an actionable fallback when a child throws', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});

        render(
            <MemoryRouter>
                <ErrorBoundary>
                    <Bomb shouldThrow />
                </ErrorBoundary>
            </MemoryRouter>
        );

        expect(screen.getByRole('alert')).toHaveTextContent(/something went wrong/i);
        expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
        expect(screen.getByRole('link', { name: /go to accounts/i })).toHaveAttribute('href', '/accounts');
    });

    it('renders children normally when nothing throws', () => {
        render(
            <MemoryRouter>
                <ErrorBoundary>
                    <Bomb shouldThrow={false} />
                </ErrorBoundary>
            </MemoryRouter>
        );

        expect(screen.getByText('Recovered')).toBeInTheDocument();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('clears the error and renders fresh children after "Try again"', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {});

        const { rerender } = render(
            <MemoryRouter>
                <ErrorBoundary>
                    <Bomb key="a" shouldThrow />
                </ErrorBoundary>
            </MemoryRouter>
        );

        expect(screen.getByRole('alert')).toBeInTheDocument();

        // Simulates the underlying condition clearing up (e.g. a transient API
        // failure) before the user clicks "Try again".
        rerender(
            <MemoryRouter>
                <ErrorBoundary>
                    <Bomb key="b" shouldThrow={false} />
                </ErrorBoundary>
            </MemoryRouter>
        );
        expect(screen.getByRole('alert')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: /try again/i }));

        expect(screen.getByText('Recovered')).toBeInTheDocument();
    });
});

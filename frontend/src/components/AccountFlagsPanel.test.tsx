import { fireEvent, render, screen, within } from '@testing-library/react';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { http, HttpResponse } from 'msw';
import { axe } from 'jest-axe';
import { describe, it, expect } from 'vitest';
import AccountFlagsPanel from './AccountFlagsPanel';
import accountFlagsReducer from '../store/accountFlagsSlice';
import { server } from '../test/msw/server';
import type { FraudFlag } from '../types/fraud';
import type { PagedResponse } from '../types/pagination';

const blocked: FraudFlag = {
    id: 'flag-1',
    transactionId: '0f8fad5b-d9cb-469f-a165-70867728950e',
    accountId: 'acc-1',
    rule: 'VELOCITY',
    detail: '4 transactions in PT10M (max 3)',
    blocked: true,
    flaggedAt: '2026-11-01T12:00:00Z',
};

const monitored: FraudFlag = {
    ...blocked,
    id: 'flag-2',
    rule: 'AMOUNT_SPIKE',
    detail: '45.01 is over 3x the 30-day DEBIT average',
    blocked: false,
};

function pageOf(items: FraudFlag[], number = 0, totalPages = 1): PagedResponse<FraudFlag> {
    return { content: items, page: { size: 20, number, totalElements: items.length, totalPages } };
}

function renderPanel(accountId = 'acc-1') {
    const store = configureStore({ reducer: { accountFlags: accountFlagsReducer } });
    return render(
        <Provider store={store}>
            <AccountFlagsPanel accountId={accountId} />
        </Provider>
    );
}

describe('AccountFlagsPanel', () => {
    it('lists the account\'s flags with rule, detail and outcome', async () => {
        server.use(http.get('/api/accounts/acc-1/flags', () => HttpResponse.json(pageOf([blocked, monitored]))));
        renderPanel();

        expect(screen.getByRole('heading', { name: 'Fraud flags' })).toBeInTheDocument();
        const table = await screen.findByRole('table', { name: 'Fraud flags' });
        const rows = within(table).getAllByRole('row').slice(1);
        expect(rows).toHaveLength(2);
        expect(rows[0]).toHaveTextContent('Velocity');
        expect(rows[0]).toHaveTextContent('4 transactions in PT10M (max 3)');
        expect(rows[0]).toHaveTextContent('BLOCKED');
        expect(rows[0]).toHaveTextContent('0f8fad5b');
        expect(rows[1]).toHaveTextContent('Amount spike');
        expect(rows[1]).toHaveTextContent('MONITORED');
    });

    it('says so when the account has no flags', async () => {
        renderPanel();
        expect(await screen.findByText('No fraud flags on this account.')).toBeInTheDocument();
    });

    it('pages through flags', async () => {
        const requested: string[] = [];
        server.use(
            http.get('/api/accounts/acc-1/flags', ({ request }) => {
                const page = Number(new URL(request.url).searchParams.get('page') ?? 0);
                requested.push(String(page));
                return HttpResponse.json(pageOf([page === 0 ? blocked : monitored], page, 2));
            })
        );
        renderPanel();

        await screen.findByText('Velocity');
        fireEvent.click(screen.getByRole('button', { name: /^next$/i }));

        expect(await screen.findByText('Amount spike')).toBeInTheDocument();
        expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
        expect(requested).toEqual(['0', '1']);
    });

    it('shows an error with a working retry', async () => {
        let calls = 0;
        server.use(
            http.get('/api/accounts/acc-1/flags', () =>
                ++calls === 1
                    ? HttpResponse.json({ detail: 'down' }, { status: 503 })
                    : HttpResponse.json(pageOf([blocked]))
            )
        );
        renderPanel();

        expect(await screen.findByRole('alert')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /retry/i }));
        expect(await screen.findByText('Velocity')).toBeInTheDocument();
    });

    it('has no detectable axe violations when populated', async () => {
        server.use(http.get('/api/accounts/acc-1/flags', () => HttpResponse.json(pageOf([blocked, monitored]))));
        const { container } = renderPanel();
        await screen.findByRole('table');
        expect(await axe(container)).toHaveNoViolations();
    });
});

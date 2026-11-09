import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import TransactionStatusBadge from './TransactionStatusBadge';
import type { TransactionRejectionReason, TransactionStatus } from '../types/transaction';

describe('TransactionStatusBadge', () => {
    it.each<[TransactionStatus, string]>([
        ['PENDING', 'status-badge--pending'],
        ['POSTED', 'status-badge--posted'],
        ['REJECTED', 'status-badge--rejected'],
        ['VOIDED', 'status-badge--voided'],
    ])('renders %s with its own style', (status, className) => {
        render(<TransactionStatusBadge status={status} />);
        expect(screen.getByText(status)).toHaveClass('status-badge', className);
    });

    it.each<[TransactionRejectionReason, string]>([
        ['ACCOUNT_NOT_FOUND', 'Account not found'],
        ['ACCOUNT_NOT_ACTIVE', 'Account is frozen or closed'],
        ['INSUFFICIENT_FUNDS', 'Insufficient funds'],
        ['SUSPECTED_FRAUD', 'Blocked as suspected fraud'],
    ])('explains a %s rejection', (rejectionReason, label) => {
        render(<TransactionStatusBadge status="REJECTED" rejectionReason={rejectionReason} />);
        expect(screen.getByText(label)).toBeInTheDocument();
    });

    it('shows no reason for a status other than REJECTED', () => {
        const { container } = render(
            <TransactionStatusBadge status="POSTED" rejectionReason="INSUFFICIENT_FUNDS" />
        );
        expect(container.querySelector('.status-reason')).toBeNull();
    });
});

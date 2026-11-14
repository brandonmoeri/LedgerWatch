import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, it, expect } from 'vitest';
import FraudFlagBadges from './FraudFlagBadges';

describe('FraudFlagBadges', () => {
    it('renders nothing without flags', () => {
        const { container } = render(<FraudFlagBadges flags={[]} />);
        expect(container).toBeEmptyDOMElement();
    });

    it.each([
        ['VELOCITY', 'Velocity'],
        ['AMOUNT_SPIKE', 'Amount spike'],
        ['NEW_ACCOUNT_LARGE_DEBIT', 'Large debit, new account'],
    ])('labels %s as "%s"', (rule, label) => {
        render(<FraudFlagBadges flags={[{ rule, detail: 'why' }]} />);
        expect(screen.getByText(label)).toHaveClass('flag-badge');
    });

    it('shows an unknown rule by its raw name', () => {
        render(<FraudFlagBadges flags={[{ rule: 'GEO_MISMATCH', detail: 'why' }]} />);
        expect(screen.getByText('GEO_MISMATCH')).toBeInTheDocument();
    });

    it('renders one badge per flag, with its detail as tooltip and screen-reader text', () => {
        render(
            <FraudFlagBadges
                flags={[
                    { rule: 'VELOCITY', detail: '4 transactions in PT10M (max 3)' },
                    { rule: 'AMOUNT_SPIKE', detail: '45.01 is over 3x the average' },
                ]}
            />
        );
        const items = screen.getAllByRole('listitem');
        expect(items).toHaveLength(2);
        expect(screen.getByText('Velocity')).toHaveAttribute('title', '4 transactions in PT10M (max 3)');
        expect(items[1]).toHaveTextContent('Amount spike: 45.01 is over 3x the average');
    });

    it('has no detectable axe violations', async () => {
        const { container } = render(<FraudFlagBadges flags={[{ rule: 'VELOCITY', detail: 'why' }]} />);
        expect(await axe(container)).toHaveNoViolations();
    });
});

import type { Meta, StoryObj } from '@storybook/react-vite';
import TransactionStatusBadge from './TransactionStatusBadge';

const meta = {
  title: 'Components/TransactionStatusBadge',
  component: TransactionStatusBadge,
  parameters: {
    layout: 'padded',
  },
} satisfies Meta<typeof TransactionStatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Pending: Story = { args: { status: 'PENDING' } };

export const Posted: Story = { args: { status: 'POSTED' } };

export const Rejected: Story = {
  args: { status: 'REJECTED', rejectionReason: 'INSUFFICIENT_FUNDS' },
};

export const Voided: Story = { args: { status: 'VOIDED' } };

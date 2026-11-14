import type { Meta, StoryObj } from '@storybook/react-vite';
import FraudFlagBadges from './FraudFlagBadges';

const meta = {
  title: 'Components/FraudFlagBadges',
  component: FraudFlagBadges,
  parameters: {
    layout: 'padded',
  },
} satisfies Meta<typeof FraudFlagBadges>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SingleFlag: Story = {
  args: { flags: [{ rule: 'VELOCITY', detail: '4 transactions in PT10M (max 3)' }] },
};

export const SeveralFlags: Story = {
  args: {
    flags: [
      { rule: 'NEW_ACCOUNT_LARGE_DEBIT', detail: 'Debit of 1500.00 on an account 2 hours old' },
      { rule: 'VELOCITY', detail: '4 transactions in PT10M (max 3)' },
    ],
  },
};

export const UnknownRule: Story = {
  args: { flags: [{ rule: 'GEO_MISMATCH', detail: 'A rule this build has no label for' }] },
};

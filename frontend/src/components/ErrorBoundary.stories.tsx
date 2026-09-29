import { MemoryRouter } from 'react-router-dom';
import type { Meta, StoryObj } from '@storybook/react-vite';
import ErrorBoundary from './ErrorBoundary';

function Bomb({ shouldThrow, message = 'boom' }: { shouldThrow: boolean; message?: string }) {
  if (shouldThrow) {
    throw new Error(message);
  }
  return <p>Page content rendered normally.</p>;
}

const meta = {
  title: 'Components/ErrorBoundary',
  component: ErrorBoundary,
  parameters: {
    layout: 'padded',
  },
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={['/accounts/123']}>
        <Story />
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof ErrorBoundary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Recovered: Story = {
  name: 'Children render normally',
  args: {
    children: <Bomb shouldThrow={false} />,
  },
};

export const CaughtError: Story = {
  name: 'Child throws during render',
  args: {
    children: <Bomb shouldThrow message="Failed to load account details" />,
  },
};

export const CaughtErrorWithoutMessage: Story = {
  name: 'Child throws with no message',
  args: {
    children: <Bomb shouldThrow message="" />,
  },
};

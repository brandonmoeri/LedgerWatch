import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import DataTable from './DataTable';
import type { DataTableColumn, DataTableSort } from './DataTable';

interface Row {
  id: string;
  name: string;
  amount: number;
  status: string;
}

const COLUMNS: DataTableColumn<Row>[] = [
  { key: 'name', header: 'Name', render: (row) => row.name, sortField: 'name' },
  { key: 'status', header: 'Status', render: (row) => row.status },
  { key: 'amount', header: 'Amount', render: (row) => `$${row.amount.toFixed(2)}`, sortField: 'amount' },
];

const ITEMS: Row[] = [
  { id: '1', name: 'Alice Nakamura', amount: 1024.5, status: 'POSTED' },
  { id: '2', name: 'Bob Ferreira', amount: 87.2, status: 'PENDING' },
  { id: '3', name: 'Charlie Osei', amount: 512, status: 'POSTED' },
  { id: '4', name: 'Dana Iyer', amount: 19.99, status: 'VOIDED' },
];

const SORT: DataTableSort = { field: 'name', direction: 'asc' };

const meta = {
  title: 'Components/DataTable',
  component: DataTable<Row>,
  parameters: {
    layout: 'padded',
  },
  args: {
    caption: 'Ledger rows',
    columns: COLUMNS,
    items: ITEMS,
    getRowKey: (row: Row) => row.id,
    status: 'succeeded',
    page: 0,
    totalPages: 1,
    onPageChange: fn(),
  },
} satisfies Meta<typeof DataTable<Row>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Populated: Story = {
  args: {
    sort: SORT,
    onSortChange: fn(),
  },
};

export const Loading: Story = {
  args: {
    status: 'loading',
  },
};

export const ErrorState: Story = {
  name: 'Error',
  args: {
    status: 'failed',
    error: 'Failed to load ledger rows. The server returned a 503.',
    onRetry: fn(),
  },
};

export const ErrorWithoutRetry: Story = {
  args: {
    status: 'failed',
    error: 'Failed to load ledger rows.',
  },
};

export const Empty: Story = {
  args: {
    items: [],
    status: 'succeeded',
    emptyMessage: 'No rows match your filters.',
  },
};

export const Idle: Story = {
  args: {
    status: 'idle',
  },
};

export const FirstPage: Story = {
  args: {
    sort: SORT,
    onSortChange: fn(),
    page: 0,
    totalPages: 5,
  },
};

export const LastPage: Story = {
  args: {
    sort: SORT,
    onSortChange: fn(),
    page: 4,
    totalPages: 5,
  },
};

export const SingleUnsortedColumnDescending: Story = {
  args: {
    sort: { field: 'amount', direction: 'desc' },
    onSortChange: fn(),
  },
};

export const NotSortable: Story = {
  name: 'Without sorting controls',
  args: {
    sort: undefined,
    onSortChange: undefined,
  },
};

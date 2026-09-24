import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { axe } from 'jest-axe';
import DataTable from './DataTable';
import type { DataTableColumn } from './DataTable';

interface Row {
  id: string;
  name: string;
  amount: number;
}

const COLUMNS: DataTableColumn<Row>[] = [
  { key: 'name', header: 'Name', render: (row) => row.name, sortField: 'name' },
  { key: 'amount', header: 'Amount', render: (row) => row.amount, sortField: 'amount' },
];

const ITEMS: Row[] = [
  { id: '1', name: 'Alice', amount: 10 },
  { id: '2', name: 'Bob', amount: 20 },
];

function renderTable(overrides: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) {
  return render(
    <DataTable
      caption="Rows"
      columns={COLUMNS}
      items={ITEMS}
      getRowKey={(row) => row.id}
      status="succeeded"
      page={0}
      totalPages={1}
      onPageChange={vi.fn()}
      {...overrides}
    />
  );
}

describe('DataTable accessibility', () => {
  it('renders semantic table markup with an accessible caption', () => {
    renderTable();
    expect(screen.getByRole('table', { name: 'Rows' })).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader')).toHaveLength(2);
  });

  it('exposes sort state via aria-sort and toggles direction on click', () => {
    const onSortChange = vi.fn();
    renderTable({ sort: { field: 'name', direction: 'asc' }, onSortChange });

    const nameHeader = screen.getByRole('columnheader', { name: /name/i });
    expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');

    const amountHeader = screen.getByRole('columnheader', { name: /amount/i });
    expect(amountHeader).toHaveAttribute('aria-sort', 'none');

    fireEvent.click(screen.getByRole('button', { name: /name/i }));
    expect(onSortChange).toHaveBeenCalledWith('name');
  });

  it('makes sortable headers keyboard-operable via native buttons', () => {
    const onSortChange = vi.fn();
    renderTable({ sort: { field: 'name', direction: 'asc' }, onSortChange });

    const sortButton = screen.getByRole('button', { name: /name/i });
    sortButton.focus();
    expect(sortButton).toHaveFocus();
    fireEvent.click(sortButton);
    expect(onSortChange).toHaveBeenCalledWith('name');
  });

  it('has no detectable axe violations when populated', async () => {
    const { container } = renderTable({ sort: { field: 'name', direction: 'asc' }, onSortChange: vi.fn() });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no detectable axe violations while loading', async () => {
    const { container } = renderTable({ status: 'loading' });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('has no detectable axe violations on error', async () => {
    const { container } = renderTable({ status: 'failed', error: 'Boom', onRetry: vi.fn() });
    expect(await axe(container)).toHaveNoViolations();
  });
});

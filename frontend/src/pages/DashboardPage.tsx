import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Link } from 'react-router-dom';
import {
    Bar,
    BarChart,
    CartesianGrid,
    Cell,
    Line,
    LineChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import type { TooltipContentProps } from 'recharts';
import { fetchDashboardSummary } from '../store/dashboardSlice';
import type { AppDispatch, RootState } from '../store/store';
import { useStableCallback } from '../hooks/useStableCallback';

const LIGHT_PALETTE = {
    surface: '#fcfcfb',
    gridline: '#e1e0d9',
    baseline: '#c3c2b7',
    muted: '#898781',
    series1: '#2a78d6',
    series2: '#eb6834',
};

const DARK_PALETTE = {
    surface: '#1a1a19',
    gridline: '#2c2c2a',
    baseline: '#383835',
    muted: '#898781',
    series1: '#3987e5',
    series2: '#d95926',
};

function supportsMatchMedia(): boolean {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function';
}

function usePrefersDark(): boolean {
    const [prefersDark, setPrefersDark] = useState(
        () => supportsMatchMedia() && window.matchMedia('(prefers-color-scheme: dark)').matches
    );
    useEffect(() => {
        if (!supportsMatchMedia()) return;
        const mql = window.matchMedia('(prefers-color-scheme: dark)');
        const listener = (e: MediaQueryListEvent) => setPrefersDark(e.matches);
        mql.addEventListener('change', listener);
        return () => mql.removeEventListener('change', listener);
    }, []);
    return prefersDark;
}

function toStartOfDayInstant(date: string): string {
    return `${date}T00:00:00.000Z`;
}

function toEndOfDayInstant(date: string): string {
    return `${date}T23:59:59.999Z`;
}

function formatCurrency(value: number): string {
    return value.toLocaleString(undefined, {
        style: 'currency',
        currency: 'USD',
        maximumFractionDigits: 0,
    });
}

function BalanceTooltip({ active, payload, label }: TooltipContentProps) {
    if (!active || !payload?.length) return null;
    return (
        <div className="viz-tooltip">
            <div className="viz-tooltip-label">{new Date(label as string).toLocaleDateString()}</div>
            <div className="viz-tooltip-value">{formatCurrency(Number(payload[0].value))}</div>
        </div>
    );
}

interface SpendDatum {
    type: string;
    total: number;
    count: number;
}

function SpendTooltip({ active, payload }: TooltipContentProps) {
    if (!active || !payload?.length) return null;
    const point = payload[0].payload as SpendDatum;
    return (
        <div className="viz-tooltip">
            <div className="viz-tooltip-label">
                {point.type} &middot; {point.count} transaction{point.count === 1 ? '' : 's'}
            </div>
            <div className="viz-tooltip-value">{formatCurrency(point.total)}</div>
        </div>
    );
}

export default function DashboardPage() {
    const dispatch = useDispatch<AppDispatch>();
    const { summary, status, error } = useSelector((s: RootState) => s.dashboard);
    const colors = usePrefersDark() ? DARK_PALETTE : LIGHT_PALETTE;

    const [accountId, setAccountId] = useState('');
    const [fromDate, setFromDate] = useState('');
    const [toDate, setToDate] = useState('');

    useEffect(() => {
        if (status === 'idle') {
            dispatch(fetchDashboardSummary({}));
        }
    }, [status, dispatch]);

    const runSearch = useStableCallback(() => {
        dispatch(
            fetchDashboardSummary({
                accountId: accountId || undefined,
                createdFrom: fromDate ? toStartOfDayInstant(fromDate) : undefined,
                createdTo: toDate ? toEndOfDayInstant(toDate) : undefined,
            })
        );
    });

    const handleSearchSubmit = (e: FormEvent) => {
        e.preventDefault();
        runSearch();
    };

    const balanceData = useMemo(
        () => (summary?.balanceOverTime ?? []).map((p) => ({ date: p.date, balance: Number(p.balance) })),
        [summary]
    );

    const spendData = useMemo<SpendDatum[]>(
        () => (summary?.spendByType ?? []).map((s) => ({ type: s.type, total: Number(s.total), count: s.count })),
        [summary]
    );

    return (
        <div>
            <h1>Dashboard</h1>
            <Link to="/accounts">Accounts</Link>
            {' | '}
            <Link to="/transactions">Transactions</Link>

            <form onSubmit={handleSearchSubmit}>
                <label>
                    Account ID
                    <input
                        type="text"
                        value={accountId}
                        onChange={(e) => setAccountId(e.target.value)}
                        placeholder="All accounts"
                    />
                </label>
                <label>
                    From
                    <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
                </label>
                <label>
                    To
                    <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
                </label>
                <button type="submit">Search</button>
            </form>

            {status === 'loading' && <p>Loading dashboard&hellip;</p>}

            {status === 'failed' && (
                <div className="state-error">
                    <p>Error: {error}</p>
                    <button type="button" onClick={runSearch}>
                        Retry
                    </button>
                </div>
            )}

            {status === 'succeeded' && (
                <div className="dashboard-charts">
                    <div className="chart-card" style={{ background: colors.surface, borderColor: colors.gridline }}>
                        <h2>Balance over time</h2>
                        {balanceData.length === 0 ? (
                            <p className="state-empty">No posted transactions in this range.</p>
                        ) : (
                            <ResponsiveContainer width="100%" height={280}>
                                <LineChart data={balanceData} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                                    <CartesianGrid vertical={false} stroke={colors.gridline} />
                                    <XAxis
                                        dataKey="date"
                                        tickFormatter={(d) =>
                                            new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
                                        }
                                        stroke={colors.baseline}
                                        tick={{ fill: colors.muted, fontSize: 12 }}
                                    />
                                    <YAxis
                                        tickFormatter={formatCurrency}
                                        stroke={colors.baseline}
                                        tick={{ fill: colors.muted, fontSize: 12 }}
                                        width={80}
                                    />
                                    <Tooltip content={BalanceTooltip} />
                                    <Line
                                        type="monotone"
                                        dataKey="balance"
                                        stroke={colors.series1}
                                        strokeWidth={2}
                                        dot={{ r: 4, fill: colors.series1, stroke: colors.surface, strokeWidth: 2 }}
                                        activeDot={{ r: 5 }}
                                        isAnimationActive={false}
                                    />
                                </LineChart>
                            </ResponsiveContainer>
                        )}
                    </div>

                    <div className="chart-card" style={{ background: colors.surface, borderColor: colors.gridline }}>
                        <h2>Spend by transaction type</h2>
                        {spendData.length === 0 ? (
                            <p className="state-empty">No posted transactions in this range.</p>
                        ) : (
                            <ResponsiveContainer width="100%" height={280}>
                                <BarChart data={spendData} margin={{ top: 16, right: 16, left: 0, bottom: 0 }}>
                                    <CartesianGrid vertical={false} stroke={colors.gridline} />
                                    <XAxis
                                        dataKey="type"
                                        stroke={colors.baseline}
                                        tick={{ fill: colors.muted, fontSize: 12 }}
                                    />
                                    <YAxis
                                        tickFormatter={formatCurrency}
                                        stroke={colors.baseline}
                                        tick={{ fill: colors.muted, fontSize: 12 }}
                                        width={80}
                                    />
                                    <Tooltip content={SpendTooltip} cursor={{ fill: colors.gridline, opacity: 0.4 }} />
                                    <Bar dataKey="total" radius={[4, 4, 0, 0]} maxBarSize={64} isAnimationActive={false}>
                                        {spendData.map((entry) => (
                                            <Cell
                                                key={entry.type}
                                                fill={entry.type === 'CREDIT' ? colors.series1 : colors.series2}
                                            />
                                        ))}
                                    </Bar>
                                </BarChart>
                            </ResponsiveContainer>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}

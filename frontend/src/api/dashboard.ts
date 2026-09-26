import client from './client';
import type { DashboardQuery, DashboardSummary } from '../types/dashboard';

const BASE = '/transactions/summary';

export const dashboardApi = {
    getSummary: (query: DashboardQuery = {}): Promise<DashboardSummary> =>
        client.get<DashboardSummary>(BASE, { params: query }).then((r) => r.data),
};

import client from './client';
import type { FraudFlag, FraudFlagsQuery } from '../types/fraud';
import type { PagedResponse } from '../types/pagination';

export const fraudFlagsApi = {
    getByAccount: (accountId: string, query: FraudFlagsQuery = {}): Promise<PagedResponse<FraudFlag>> =>
        client
            .get<PagedResponse<FraudFlag>>(`/accounts/${accountId}/flags`, { params: query })
            .then((r) => r.data),
};

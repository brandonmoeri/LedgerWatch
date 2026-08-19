import axios, { AxiosError } from 'axios';
import { auth } from '../auth/auth';

export interface ApiError {
    status: number;
    detail: string;
    errors?: Record<string, string>;
}

const client = axios.create({
    baseURL: '/api',
    headers: { 'Content-Type': 'application/json' },
    timeout: 10_000,
});

client.interceptors.request.use((config) => {
    const token = auth.getToken();
    if (token) {
        config.headers.Authorization= `Bearer ${token}`;
    }
    return config;
});

client.interceptors.response.use(
    (res) => res,
    (err: AxiosError<{ detail?: string; errors?: Record<string, string> }>) => {
        const apiErr: ApiError = {
            status: err.response?.status ?? 0,
            detail: err.response?.data?.detail ?? err.message,
            errors: err.response?.data?.errors,
        };
        return Promise.reject(apiErr);
    },
);

export default client;
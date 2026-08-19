import client from './client';

export interface LoginRequest {
    username: string;
    password: string;
}

export interface LoginResponse {
    token: string;
    tokenType: string;
    expiresIn: number;
}

export const authApi = {
    login: (body: LoginRequest): Promise<LoginResponse> =>
        client.post<LoginResponse>('/auth/login', body).then((r) => r.data),
};

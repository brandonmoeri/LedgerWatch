const TOKEN_KEY = 'ledgerwatch_auth_token';

export interface AuthUser {
    sub: string;
    roles: string[];
    exp: number;
}

function parsePayload(token: string): AuthUser | null {
    try {
        const [, payload] = token.split('.');
        const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
        return JSON.parse(json) as AuthUser;
    } catch {
        return null;
    }
}

export const auth = {
    getToken(): string | null {
        return localStorage.getItem(TOKEN_KEY);
    },

    getUser(): AuthUser | null {
        const token = this.getToken();
        return token ? parsePayload(token) : null;
    },

    isAuthenticated(): boolean {
        const user = this.getUser();
        return user !== null && user.exp > Date.now() / 1_000;
    },

    setToken(token: string): void {
        localStorage.setItem(TOKEN_KEY, token);
    },

    clearToken(): void {
        localStorage.removeItem(TOKEN_KEY);
    },
};

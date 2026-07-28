// Mock JWT - header.payload.signature (base64url)
// Payload decodes to: { sub, name, roles, iat, exp }
// exp is year 2286 -- effectively never expires while in stub mode.

const MOCK_JWT =
    'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9' +
    '.eyJzdWIiOiJtb2NrLXVzZXItMDAxIiwibmFtZSI6Ik1vY2sgVXNlciIsInJvbGVzIjpbIlVTRVIiXSwiaWF0IjoxNzUzNTc0NDAwLCJleHAiOjk5OTk5OTk5OTl9' +
    '.stub-signature-not-validated';

const TOKEN_KEY = 'ledgerwatch_auth_token';

export interface AuthUser {
    sub: string;
    name: string;
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

export const authStub = {
    /** Call once at app startup. Seeds localStorage with the mock token if absent. */
    init(): void {
        if (!localStorage.getItem(TOKEN_KEY)) {
            localStorage.setItem(TOKEN_KEY, MOCK_JWT);
        }
    },

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

    /** Swap in a real token when you wire up a proper auth server. */
    setToken(token: string): void {
        localStorage.setItem(TOKEN_KEY, token);
    },

    clearToken(): void {
        localStorage.removeItem(TOKEN_KEY);
    },
};
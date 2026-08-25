import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { auth } from '../auth/auth';
import type { ApiError } from '../api/client';

export default function LoginPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState<ApiError | null>(null);
    const [submitting, setSubmitting] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        setSubmitting(true);
        try {
            const { token } = await authApi.login({ username, password });
            auth.setToken(token);
            const from = (location.state as { from?: string } | null)?.from ?? '/accounts';
            navigate(from, { replace: true });
        } catch (err) {
            setError(err as ApiError);
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <form onSubmit={handleSubmit}>
            <h1>Log In</h1>
            <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="Username"
                autoFocus
                required
            />
            <input
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Password"
                type="password"
                required
            />
            <button type="submit" disabled={submitting}>
                {submitting ? 'Logging in…' : 'Log In'}
            </button>
            {error && <p>{error.detail}</p>}
        </form>
    );
}

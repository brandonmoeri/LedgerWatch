import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { Link } from 'react-router-dom';

interface ErrorBoundaryProps {
    children: ReactNode;
}

interface ErrorBoundaryState {
    error: Error | null;
}

export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
    state: ErrorBoundaryState = { error: null };

    static getDerivedStateFromError(error: Error): ErrorBoundaryState {
        return { error };
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('Unhandled error rendering the app', error, info.componentStack);
    }

    private handleRetry = () => {
        this.setState({ error: null });
    };

    render() {
        if (this.state.error) {
            return (
                <div role="alert" className="state-error">
                    <h1>Something went wrong</h1>
                    <p>
                        We hit an unexpected error loading this page. You can try again, or head
                        back to your accounts.
                    </p>
                    <button onClick={this.handleRetry}>Try again</button>
                    <Link to="/accounts">Go to accounts</Link>
                </div>
            );
        }

        return this.props.children;
    }
}

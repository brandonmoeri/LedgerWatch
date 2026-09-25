import { Link } from 'react-router-dom';

export default function NotFoundPage() {
    return (
        <div>
            <h1>Page not found</h1>
            <p>The page you're looking for doesn't exist or may have been moved.</p>
            <Link to="/accounts">Go to accounts</Link>
        </div>
    );
}

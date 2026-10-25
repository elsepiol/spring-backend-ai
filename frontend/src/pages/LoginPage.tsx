import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { useShop } from '../auth/ShopContext';
import { ErrorMessage } from '../components/ErrorMessage';

export function LoginPage() {
  const { login, sessionMessage } = useShop();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      navigate(from, { replace: true }); // back to where the user was sent from
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  // The server says the same thing for an unknown email and a wrong password (Level 2): so do we.
  const wrongCredentials = error instanceof ApiError && error.status === 401;

  return (
    <section className="narrow">
      <h1>Log in</h1>
      {sessionMessage && <p role="status" className="notice">{sessionMessage}</p>}
      <form onSubmit={onSubmit}>
        <label>
          Email
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Password
          <input type="password" autoComplete="current-password" required value={password}
            onChange={(e) => setPassword(e.target.value)} />
        </label>
        {wrongCredentials ? <p role="alert" className="error">Invalid email or password.</p> : <ErrorMessage error={error} />}
        <button type="submit" disabled={busy}>{busy ? 'Logging in...' : 'Log in'}</button>
      </form>
      <p>No account? <Link to="/register">Create one</Link></p>
    </section>
  );
}

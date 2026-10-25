import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { useShop } from '../auth/ShopContext';
import { ErrorMessage } from '../components/ErrorMessage';

export function RegisterPage() {
  const { api, login } = useShop();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.register(email, password);
      await login(email, password);
      navigate('/', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  // The SERVER's validation decides (Level 1). The browser's required/minLength are only a
  // convenience: anyone can send any request without our frontend.
  const fieldErrors = error instanceof ApiError ? error.fieldErrors : {};
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;

  return (
    <section className="narrow">
      <h1>Create an account</h1>
      <form onSubmit={onSubmit} noValidate>
        <label>
          Email
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)}
            aria-invalid={Boolean(fieldErrors.email)} aria-describedby="email-error" />
        </label>
        {fieldErrors.email && <p id="email-error" className="field-error">{fieldErrors.email}</p>}
        <label>
          Password (10 to 72 characters)
          <input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)}
            aria-invalid={Boolean(fieldErrors.password)} aria-describedby="password-error" />
        </label>
        {fieldErrors.password && <p id="password-error" className="field-error">{fieldErrors.password}</p>}
        {!hasFieldErrors && <ErrorMessage error={error} />}
        <button type="submit" disabled={busy}>{busy ? 'Creating...' : 'Create account'}</button>
      </form>
      <p>Already registered? <Link to="/login">Log in</Link></p>
    </section>
  );
}

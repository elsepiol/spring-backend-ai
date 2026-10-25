import { useEffect, useState } from 'react';
import type { Notification } from '../api/types';
import { useShop } from '../auth/ShopContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDate } from '../format';

export const POLL_INTERVAL_MS = 5_000;

/**
 * Notifications are written by the background outbox worker (Level 5), a moment AFTER the action
 * that causes them. The page therefore polls: asks again every few seconds while it is open.
 * (Alternatives that push instead of poll: Server-Sent Events or WebSockets.)
 */
export function NotificationsPage() {
  const { api } = useShop();
  const [notifications, setNotifications] = useState<Notification[] | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const latest = await api.notifications();
        if (!cancelled) {
          setNotifications(latest);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e);
        }
      }
    }
    poll();
    const timer = setInterval(poll, POLL_INTERVAL_MS);
    return () => { // leaving the page stops the polling
      cancelled = true;
      clearInterval(timer);
    };
  }, [api]);

  return (
    <section>
      <h1>Notifications</h1>
      <p className="hint">Updates every few seconds.</p>
      <ErrorMessage error={error} />
      {notifications && notifications.length === 0 && <p>Nothing yet.</p>}
      <ul className="notifications" aria-live="polite">
        {notifications?.map((n) => <li key={n.id}>{n.message} <time dateTime={n.createdAt}>{formatDate(n.createdAt)}</time></li>)}
      </ul>
    </section>
  );
}

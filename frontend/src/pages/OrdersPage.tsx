import { useCallback, useEffect, useState } from 'react';
import type { Order, Page } from '../api/types';
import { useShop } from '../auth/ShopContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatDate, formatPrice } from '../format';

export function OrdersPage() {
  const { api } = useShop();
  const [pageNumber, setPageNumber] = useState(0);
  const [page, setPage] = useState<Page<Order> | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async (number: number) => {
    setError(null);
    try {
      setPage(await api.myOrders(number));
    } catch (e) {
      setError(e);
    }
  }, [api]);

  useEffect(() => {
    load(pageNumber);
  }, [load, pageNumber]);

  async function cancel(id: number) {
    setError(null);
    try {
      const cancelled = await api.cancelOrder(id);
      setPage((current) => current && ({ ...current,
        items: current.items.map((order) => order.id === id ? cancelled : order) }));
    } catch (e) {
      setError(e);
    }
  }

  return (
    <section>
      <h1>Your orders</h1>
      <ErrorMessage error={error} />
      {page && page.items.length === 0 && <p>No orders yet.</p>}
      <ul className="orders">
        {page?.items.map((order) => (
          <li key={order.id}>
            <strong>Order #{order.id}</strong> {formatDate(order.createdAt)}, {formatPrice(order.totalCents)},{' '}
            <span className={`status status-${order.status.toLowerCase()}`}>{order.status.toLowerCase()}</span>
            {order.status === 'PLACED' && (
              <button type="button" className="link" onClick={() => cancel(order.id)}>Cancel</button>
            )}
          </li>
        ))}
      </ul>
      {page && page.totalPages > 1 && (
        <nav className="pager" aria-label="Order pages">
          <button type="button" disabled={pageNumber === 0} onClick={() => setPageNumber(pageNumber - 1)}>Newer</button>
          <span>Page {pageNumber + 1} of {page.totalPages}</span>
          <button type="button" disabled={pageNumber + 1 >= page.totalPages}
            onClick={() => setPageNumber(pageNumber + 1)}>Older</button>
        </nav>
      )}
    </section>
  );
}

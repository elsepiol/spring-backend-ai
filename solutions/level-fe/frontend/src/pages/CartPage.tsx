import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client';
import { retrying } from '../api/retry';
import type { Order } from '../api/types';
import { useShop } from '../auth/ShopContext';
import { useCart } from '../cart/CartContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatPrice } from '../format';

/**
 * Checkout. The important part is the Idempotency-Key (Level 3):
 *  - one key per PURCHASE ATTEMPT, created when the customer first presses "Place order";
 *  - kept while that attempt is retried (the customer pressing again after an error or a timeout
 *    is the same purchase, so the server must not take the money twice);
 *  - replaced when the cart changes, because that is a different purchase;
 *  - and the button is disabled while a request is in flight, so a double click sends one request.
 *
 * SOLUTION (frontend exercise): a dropped connection or a 502/503/504 is retried automatically
 * with the same key (Part B), and after a 429 the button waits out Retry-After (Part A).
 */
export function CartPage() {
  const { api, session } = useShop();
  const { lines, remove, clear, totalCents } = useCart();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [placed, setPlaced] = useState<Order | null>(null);
  const attemptKey = useRef<string | null>(null);
  // Part A: after a 429, no new attempt until this time (epoch ms). `now` ticks once a second.
  const [waitUntil, setWaitUntil] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (waitUntil === null) {
      return;
    }
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= waitUntil) {
        setWaitUntil(null);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [waitUntil]);
  const secondsLeft = waitUntil === null ? 0 : Math.max(0, Math.ceil((waitUntil - now) / 1000));

  const cartSignature = lines.map((line) => `${line.product.id}x${line.quantity}`).join(',');
  useEffect(() => {
    attemptKey.current = null; // a different cart is a different purchase attempt
  }, [cartSignature]);

  async function placeOrder() {
    if (submitting) {
      return;
    }
    attemptKey.current ??= crypto.randomUUID();
    setSubmitting(true);
    setError(null);
    try {
      const key = attemptKey.current;
      // Part B: safe to repeat, because every repeat carries the same Idempotency-Key.
      const result = await retrying(() => api.placeOrder(
        lines.map((line) => ({ productId: line.product.id, quantity: line.quantity })), key));
      setPlaced(result.order);
      clear();
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.status === 422) {
        attemptKey.current = null; // the server says this key belongs to a different request
      }
      if (e instanceof ApiError && e.status === 429) {
        // The order was refused before it ran: keep the key, wait as long as the server asks.
        const current = Date.now();
        setNow(current);
        setWaitUntil(current + (e.retryAfterSeconds ?? 5) * 1000);
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (placed) {
    return (
      <section>
        <h1>Thank you</h1>
        <p role="status">Order #{placed.id} was placed. Total {formatPrice(placed.totalCents)}.</p>
        <p><Link to="/orders">See your orders</Link> or <Link to="/">keep shopping</Link>.</p>
      </section>
    );
  }
  if (lines.length === 0) {
    return <section><h1>Your cart</h1><p>Your cart is empty. <Link to="/">Browse products</Link></p></section>;
  }

  const outOfStock = error instanceof ApiError && error.status === 409 && typeof error.body.productId === 'number'
    ? lines.find((line) => line.product.id === error.body.productId)?.product.name : undefined;

  return (
    <section>
      <h1>Your cart</h1>
      <table>
        <thead><tr><th>Product</th><th>Quantity</th><th>Price</th><th /></tr></thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.product.id}>
              <td>{line.product.name}</td>
              <td>{line.quantity}</td>
              <td>{formatPrice(line.product.priceCents * line.quantity)}</td>
              <td><button type="button" className="link" onClick={() => remove(line.product.id)}>Remove</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="total">Total {formatPrice(totalCents)}</p>
      {outOfStock
        ? <p role="alert" className="error">Not enough stock left for {outOfStock}. Lower the quantity or remove it.</p>
        : <ErrorMessage error={error} />}
      {session
        ? (
          <button type="button" onClick={placeOrder} disabled={submitting || waitUntil !== null}>
            {waitUntil !== null ? `Try again in ${secondsLeft} s` : submitting ? 'Placing order...' : 'Place order'}
          </button>
        )
        : <p><Link to="/login" state={{ from: '/cart' }}>Log in</Link> to place your order.</p>}
    </section>
  );
}

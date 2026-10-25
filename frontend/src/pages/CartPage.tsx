import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client';
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
 */
export function CartPage() {
  const { api, session } = useShop();
  const { lines, remove, clear, totalCents } = useCart();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [placed, setPlaced] = useState<Order | null>(null);
  const attemptKey = useRef<string | null>(null);

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
      const result = await api.placeOrder(
        lines.map((line) => ({ productId: line.product.id, quantity: line.quantity })), attemptKey.current);
      setPlaced(result.order);
      clear();
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.status === 422) {
        attemptKey.current = null; // the server says this key belongs to a different request
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
        ? <button type="button" onClick={placeOrder} disabled={submitting}>{submitting ? 'Placing order...' : 'Place order'}</button>
        : <p><Link to="/login" state={{ from: '/cart' }}>Log in</Link> to place your order.</p>}
    </section>
  );
}

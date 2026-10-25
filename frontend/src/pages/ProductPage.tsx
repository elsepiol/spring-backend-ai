import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';
import type { Product } from '../api/types';
import { useShop } from '../auth/ShopContext';
import { useCart } from '../cart/CartContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatPrice } from '../format';

export function ProductPage() {
  const { id } = useParams();
  const { api } = useShop();
  const { add } = useCart();
  const [product, setProduct] = useState<Product | null>(null);
  const [stock, setStock] = useState<number | null>(null);
  // What the user typed, as typed: clamping on every keystroke would make the box impossible to
  // clear and retype. It is checked when the form is submitted.
  const [quantityText, setQuantityText] = useState('1');
  const [quantityError, setQuantityError] = useState<string | null>(null);
  const [added, setAdded] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let cancelled = false;
    const productId = Number(id);
    // Two independent requests: send both at once instead of one after the other.
    Promise.all([api.product(productId), api.stock(productId)])
      .then(([p, s]) => {
        if (!cancelled) {
          setProduct(p);
          setStock(s.quantity);
        }
      })
      .catch((e) => !cancelled && setError(e));
    return () => {
      cancelled = true;
    };
  }, [api, id]);

  if (error instanceof ApiError && error.status === 404) {
    return <p>This product does not exist. <Link to="/">Back to products</Link></p>;
  }
  if (error) {
    return <ErrorMessage error={error} />;
  }
  if (!product || stock === null) {
    return <p aria-busy="true">Loading...</p>;
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const quantity = Number(quantityText);
    const most = Math.min(100, stock!);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > most) {
      setQuantityError(`Enter a whole number from 1 to ${most}.`);
      setAdded(false);
      return;
    }
    setQuantityError(null);
    add(product!, quantity);
    setAdded(true);
  }

  return (
    <section>
      <h1>{product.name}</h1>
      {product.description && <p>{product.description}</p>}
      <p className="price">{formatPrice(product.priceCents)}</p>
      <p>{stock > 0 ? `${stock} in stock` : 'Out of stock'}</p>
      {stock > 0 && (
        <form onSubmit={onSubmit} className="inline" noValidate>
          <label>
            Quantity
            <input type="number" inputMode="numeric" min={1} max={Math.min(100, stock)} value={quantityText}
              onChange={(e) => setQuantityText(e.target.value)} aria-invalid={Boolean(quantityError)}
              aria-describedby="quantity-error" />
          </label>
          <button type="submit">Add to cart</button>
        </form>
      )}
      {quantityError && <p id="quantity-error" className="field-error">{quantityError}</p>}
      {added && <p role="status">Added to your cart. <Link to="/cart">Go to cart</Link></p>}
    </section>
  );
}

import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import type { Product } from '../api/types';
import { useShop } from '../auth/ShopContext';
import { ErrorMessage } from '../components/ErrorMessage';
import { formatPrice } from '../format';

/**
 * The catalog, cheapest first, with "Load more" (keyset pagination, Level 4). The page keeps the
 * nextCursor of the last page and sends it back; it never computes page numbers or offsets.
 */
export function ProductsPage() {
  const { api } = useShop();
  const [products, setProducts] = useState<Product[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let cancelled = false; // the page may be left before the answer arrives
    api.scrollProducts(null)
      .then((page) => {
        if (!cancelled) {
          setProducts(page.items);
          setNextCursor(page.nextCursor);
        }
      })
      .catch((e) => !cancelled && setError(e))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [api]);

  async function loadMore() {
    setLoading(true);
    setError(null);
    try {
      const page = await api.scrollProducts(nextCursor);
      setProducts((current) => [...current, ...page.items]);
      setNextCursor(page.nextCursor);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section>
      <h1>Products</h1>
      <p className="hint">Cheapest first.</p>
      <ul className="products">
        {products.map((product) => (
          <li key={product.id}>
            <Link to={`/products/${product.id}`}>{product.name}</Link>
            <span className="price">{formatPrice(product.priceCents)}</span>
          </li>
        ))}
      </ul>
      {!loading && !error && products.length === 0 && <p>No products yet.</p>}
      <ErrorMessage error={error} />
      {nextCursor && (
        <button type="button" onClick={loadMore} disabled={loading}>
          {loading ? 'Loading...' : 'Load more'}
        </button>
      )}
      {loading && !nextCursor && products.length === 0 && <p aria-busy="true">Loading...</p>}
    </section>
  );
}

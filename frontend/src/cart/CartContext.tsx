import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Product } from '../api/types';

export interface CartLine {
  product: Product;
  quantity: number;
}

interface CartContextValue {
  lines: CartLine[];
  add: (product: Product, quantity: number) => void;
  remove: (productId: number) => void;
  clear: () => void;
  totalCents: number;
}

const CartContext = createContext<CartContextValue | null>(null);

/**
 * The cart lives in the browser only, until checkout. Prices shown here are what the product
 * cost when it was added; the server decides the real price when the order is placed (Level 3
 * snapshots it), so a client can never pay what it claims a product costs.
 */
export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, setLines] = useState<CartLine[]>([]);

  const add = useCallback((product: Product, quantity: number) => {
    setLines((current) => {
      const existing = current.find((line) => line.product.id === product.id);
      if (existing) {
        return current.map((line) => line.product.id === product.id
          ? { ...line, quantity: Math.min(100, line.quantity + quantity) } : line);
      }
      return [...current, { product, quantity }];
    });
  }, []);
  const remove = useCallback((productId: number) =>
    setLines((current) => current.filter((line) => line.product.id !== productId)), []);
  const clear = useCallback(() => setLines([]), []);
  const totalCents = lines.reduce((sum, line) => sum + line.product.priceCents * line.quantity, 0);

  const value = useMemo(() => ({ lines, add, remove, clear, totalCents }), [lines, add, remove, clear, totalCents]);
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) {
    throw new Error('useCart must be used inside <CartProvider>');
  }
  return value;
}

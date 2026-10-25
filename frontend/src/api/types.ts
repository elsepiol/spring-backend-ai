/** The JSON shapes of the Spring Boot API. Keep them in step with the Java records. */

export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string | null;
  priceCents: number;
  createdAt: string;
}

/** Keyset pagination (Level 4): pass nextCursor back to get the next page; null = the end. */
export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

/** Offset pagination (Level 1). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Stock {
  productId: number;
  quantity: number;
}

export interface OrderItem {
  productId: number;
  quantity: number;
  unitPriceCents: number;
}

export interface Order {
  id: number;
  status: 'PLACED' | 'CANCELLED';
  totalCents: number;
  createdAt: string;
  items: OrderItem[];
}

export interface Notification {
  id: number;
  message: string;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

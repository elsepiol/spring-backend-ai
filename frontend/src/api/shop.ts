import type { ApiClient } from './client';
import type { CursorPage, Notification, Order, Page, Product, Stock, TokenResponse } from './types';

export interface OrderLine {
  productId: number;
  quantity: number;
}

export interface PlacedOrder {
  order: Order;
  /** true when the server recognised the Idempotency-Key and returned the ORIGINAL order (Level 3). */
  replayed: boolean;
}

/** One function per endpoint the frontend uses. Components call these, never fetch() directly. */
export function shopApi(api: ApiClient) {
  return {
    scrollProducts: async (cursor: string | null, size = 12) =>
      (await api.request<CursorPage<Product>>('GET',
        `/products/scroll?size=${size}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)).data,

    product: async (id: number) => (await api.request<Product>('GET', `/products/${id}`)).data,

    stock: async (id: number) => (await api.request<Stock>('GET', `/products/${id}/stock`)).data,

    register: async (email: string, password: string) => {
      await api.request<unknown>('POST', '/auth/register', { body: { email, password } });
    },

    login: async (email: string, password: string) =>
      (await api.request<TokenResponse>('POST', '/auth/login', { body: { email, password } })).data,

    /**
     * The same idempotencyKey MUST be sent again when retrying the same purchase attempt: then the
     * server returns the first order instead of charging twice.
     */
    placeOrder: async (lines: OrderLine[], idempotencyKey: string): Promise<PlacedOrder> => {
      const response = await api.request<Order>('POST', '/orders',
        { body: { items: lines }, headers: { 'Idempotency-Key': idempotencyKey } });
      return { order: response.data, replayed: response.headers.get('Idempotent-Replayed') === 'true' };
    },

    myOrders: async (page = 0, size = 10) =>
      (await api.request<Page<Order>>('GET', `/orders?page=${page}&size=${size}`)).data,

    cancelOrder: async (id: number) => (await api.request<Order>('POST', `/orders/${id}/cancel`)).data,

    notifications: async () => (await api.request<Notification[]>('GET', '/notifications')).data,
  };
}

export type ShopApi = ReturnType<typeof shopApi>;

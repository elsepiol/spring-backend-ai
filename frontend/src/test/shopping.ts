import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, fakeToken, json, mug } from './fakeApi';
import { renderApp } from './renderApp';

export const placedOrder = { id: 99, status: 'PLACED', totalCents: 2500, createdAt: '2026-01-01T00:00:00Z', items: [] };

type User = ReturnType<typeof userEvent.setup>;

/** Logged in, the mug added to the cart through its product page (as a customer would), cart open. */
export async function putMugInCart(server: ReturnType<typeof fakeApi>,
  { quantity = 2, user = userEvent.setup() }: { quantity?: number; user?: User } = {}) {
  server.on('GET /products/7', json(mug)).on('GET /products/7/stock', json({ productId: 7, quantity: 10 }));
  renderApp('/products/7', server.fetch, { token: fakeToken() });
  const input = await screen.findByLabelText('Quantity');
  await user.clear(input);
  await user.type(input, String(quantity));
  await user.click(screen.getByRole('button', { name: 'Add to cart' }));
  await user.click(screen.getByRole('link', { name: /Go to cart/ }));
}

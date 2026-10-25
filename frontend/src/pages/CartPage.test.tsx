import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { fakeApi, fakeToken, json, mug, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

const placedOrder = { id: 99, status: 'PLACED', totalCents: 2500, createdAt: '2026-01-01T00:00:00Z', items: [] };

/** Puts the mug in the cart through the product page, as a customer would, then opens the cart. */
async function mugInCart(server: ReturnType<typeof fakeApi>, quantity = 2) {
  server.on('GET /products/7', json(mug)).on('GET /products/7/stock', json({ productId: 7, quantity: 10 }));
  renderApp('/products/7', server.fetch, { token: fakeToken() });
  const input = await screen.findByLabelText('Quantity');
  await userEvent.clear(input);
  await userEvent.type(input, String(quantity));
  await userEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
  await userEvent.click(screen.getByRole('link', { name: /Go to cart/ }));
}

describe('checkout', () => {
  it('places the order with an Idempotency-Key and empties the cart', async () => {
    const server = fakeApi().on('POST /orders', json(placedOrder, 201));
    await mugInCart(server);

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Order #99 was placed');
    const [call] = server.callsTo('POST /orders');
    expect(call.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(call.body).toEqual({ items: [{ productId: 7, quantity: 2 }] });
  });

  it('sends ONE request when the button is clicked twice quickly', async () => {
    let answer!: (response: Response) => void;
    const server = fakeApi().on('POST /orders', () => new Promise<Response>((resolve) => { answer = resolve; }));
    await mugInCart(server);

    const button = screen.getByRole('button', { name: 'Place order' });
    await userEvent.click(button);
    await userEvent.click(button);
    answer(json(placedOrder, 201));

    await screen.findByText(/Order #99 was placed/);
    expect(server.callsTo('POST /orders')).toHaveLength(1);
  });

  it('retrying after a failure is the SAME purchase: it reuses the key', async () => {
    const server = fakeApi().on('POST /orders', problem(503, 'busy'), json(placedOrder, 201));
    await mugInCart(server);

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The shop is busy right now');
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    await screen.findByText(/Order #99 was placed/);
    const [first, second] = server.callsTo('POST /orders');
    expect(second.headers.get('Idempotency-Key')).toBe(first.headers.get('Idempotency-Key'));
  });

  it('a changed cart is a NEW purchase: it gets a new key', async () => {
    const server = fakeApi()
      .on('POST /orders', problem(503, 'busy'), json(placedOrder, 201))
      .on('GET /products/scroll', json({ items: [mug], nextCursor: null }));
    await mugInCart(server);
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    await screen.findByRole('alert');

    // The customer goes back and adds one more mug: a different purchase.
    await userEvent.click(screen.getByRole('link', { name: 'Products' }));
    await userEvent.click(await screen.findByRole('link', { name: 'Blue Mug' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Add to cart' }));
    await userEvent.click(screen.getByRole('link', { name: /Go to cart/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    await screen.findByText(/Order #99 was placed/);
    const [first, second] = server.callsTo('POST /orders');
    expect(second.body).toEqual({ items: [{ productId: 7, quantity: 3 }] });
    expect(second.headers.get('Idempotency-Key')).not.toBe(first.headers.get('Idempotency-Key'));
  });

  it('names the product that ran out of stock', async () => {
    const server = fakeApi().on('POST /orders', problem(409, 'Insufficient stock for product 7', { productId: 7 }));
    await mugInCart(server);

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Not enough stock left for Blue Mug');
  });
});

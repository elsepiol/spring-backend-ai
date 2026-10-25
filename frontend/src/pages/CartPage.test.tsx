import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { fakeApi, json, mug, problem } from '../test/fakeApi';
import { placedOrder, putMugInCart } from '../test/shopping';

describe('checkout', () => {
  it('places the order with an Idempotency-Key and empties the cart', async () => {
    const server = fakeApi().on('POST /orders', json(placedOrder, 201));
    await putMugInCart(server);

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Order #99 was placed');
    const [call] = server.callsTo('POST /orders');
    expect(call.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(call.body).toEqual({ items: [{ productId: 7, quantity: 2 }] });
  });

  it('sends ONE request when the button is clicked twice quickly', async () => {
    let answer!: (response: Response) => void;
    const server = fakeApi().on('POST /orders', () => new Promise<Response>((resolve) => { answer = resolve; }));
    await putMugInCart(server);

    const button = screen.getByRole('button', { name: 'Place order' });
    await userEvent.click(button);
    await userEvent.click(button);
    answer(json(placedOrder, 201));

    await screen.findByText(/Order #99 was placed/);
    expect(server.callsTo('POST /orders')).toHaveLength(1);
  });

  it('retrying after a failure is the SAME purchase: it reuses the key', async () => {
    // A 500 leaves the outcome unknown: the order may or may not exist. Pressing the button again
    // must not risk a second order, so it carries the same key.
    const server = fakeApi().on('POST /orders', problem(500, 'boom'), json(placedOrder, 201));
    await putMugInCart(server);

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong on our side');
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    await screen.findByText(/Order #99 was placed/);
    const [first, second] = server.callsTo('POST /orders');
    expect(second.headers.get('Idempotency-Key')).toBe(first.headers.get('Idempotency-Key'));
  });

  it('a changed cart is a NEW purchase: it gets a new key', async () => {
    const server = fakeApi()
      .on('POST /orders', problem(500, 'boom'), json(placedOrder, 201))
      .on('GET /products/scroll', json({ items: [mug], nextCursor: null }));
    await putMugInCart(server);
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
    await putMugInCart(server);

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Not enough stock left for Blue Mug');
  });
});

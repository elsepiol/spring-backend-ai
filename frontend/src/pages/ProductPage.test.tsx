import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { fakeApi, json, mug, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

function productWithStock(quantity: number) {
  return fakeApi().on('GET /products/7', json(mug)).on('GET /products/7/stock', json({ productId: 7, quantity }));
}

describe('the product page', () => {
  it('lets you clear the quantity and type a new one', async () => {
    renderApp('/products/7', productWithStock(10).fetch);
    const quantity = await screen.findByLabelText('Quantity');

    await userEvent.clear(quantity);
    await userEvent.type(quantity, '3');
    await userEvent.click(screen.getByRole('button', { name: 'Add to cart' }));

    expect(screen.getByRole('status')).toHaveTextContent('Added to your cart');
    expect(screen.getByRole('link', { name: /Cart \(3\)/ })).toBeInTheDocument();
  });

  it('refuses more than is in stock, with a message instead of silence', async () => {
    renderApp('/products/7', productWithStock(4).fetch);
    const quantity = await screen.findByLabelText('Quantity');

    await userEvent.clear(quantity);
    await userEvent.type(quantity, '5');
    await userEvent.click(screen.getByRole('button', { name: 'Add to cart' }));

    expect(screen.getByText('Enter a whole number from 1 to 4.')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('has no add button when out of stock', async () => {
    renderApp('/products/7', productWithStock(0).fetch);

    expect(await screen.findByText('Out of stock')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add to cart' })).not.toBeInTheDocument();
  });

  it('says so when the product does not exist', async () => {
    const server = fakeApi().on('GET /products/7', problem(404, 'Product 7 not found'))
      .on('GET /products/7/stock', problem(404, 'Product 7 not found'));
    renderApp('/products/7', server.fetch);

    expect(await screen.findByText(/This product does not exist/)).toBeInTheDocument();
  });
});

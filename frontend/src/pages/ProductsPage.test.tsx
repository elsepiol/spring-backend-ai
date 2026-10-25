import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { fakeApi, json, mug, plate, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

describe('the product list', () => {
  it('shows the first page and loads more with the cursor until there is no next page', async () => {
    const server = fakeApi().on('GET /products/scroll',
      json({ items: [plate], nextCursor: 'CURSOR-1' }),
      json({ items: [mug], nextCursor: null }));
    renderApp('/', server.fetch);

    expect(await screen.findByRole('link', { name: 'Plate' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByRole('link', { name: 'Blue Mug' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Plate' })).toBeInTheDocument(); // appended, not replaced
    expect(server.calls[1].path).toContain('cursor=CURSOR-1');
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('shows prices from cents', async () => {
    const server = fakeApi().on('GET /products/scroll', json({ items: [mug], nextCursor: null }));
    renderApp('/', server.fetch);

    expect(await screen.findByText('12.50')).toBeInTheDocument();
  });

  it('shows a readable error with the trace id for support', async () => {
    const server = fakeApi().on('GET /products/scroll', problem(500, 'boom', { traceId: '4bf92f3577b34da6' }));
    renderApp('/', server.fetch);

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong on our side');
    expect(screen.getByRole('alert')).toHaveTextContent('4bf92f3577b34da6');
  });
});

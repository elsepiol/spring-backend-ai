import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { App } from '../App';
import { ShopProvider } from '../auth/ShopContext';
import { CartProvider } from '../cart/CartContext';

/** The whole app at one URL, talking to a fake API instead of Spring Boot. */
export function renderApp(path: string, fetch: typeof globalThis.fetch, options: { token?: string } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ShopProvider fetch={fetch} initialToken={options.token}>
        <CartProvider>
          <App />
        </CartProvider>
      </ShopProvider>
    </MemoryRouter>,
  );
}

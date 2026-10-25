import type { ReactNode } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router';
import { useShop } from './auth/ShopContext';
import { useCart } from './cart/CartContext';
import { CartPage } from './pages/CartPage';
import { LoginPage } from './pages/LoginPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OrdersPage } from './pages/OrdersPage';
import { ProductPage } from './pages/ProductPage';
import { ProductsPage } from './pages/ProductsPage';
import { RegisterPage } from './pages/RegisterPage';

/**
 * Hides pages that need a login. This is only for a pleasant experience: the SERVER is what
 * protects the data (deny by default, Level 2). Anyone can call the API without this frontend.
 */
function RequireLogin({ children }: { children: ReactNode }) {
  const { session } = useShop();
  const location = useLocation();
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}

export function App() {
  const { session, logout } = useShop();
  const { lines } = useCart();
  const items = lines.reduce((sum, line) => sum + line.quantity, 0);

  return (
    <>
      <header>
        <nav aria-label="Main">
          <NavLink to="/" end>Products</NavLink>
          <NavLink to="/cart">Cart{items > 0 && ` (${items})`}</NavLink>
          {session && <NavLink to="/orders">Orders</NavLink>}
          {session && <NavLink to="/notifications">Notifications</NavLink>}
          <span className="spacer" />
          {session
            ? <><span className="who">{session.email}</span><button type="button" className="link" onClick={() => logout()}>Log out</button></>
            : <NavLink to="/login">Log in</NavLink>}
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<ProductsPage />} />
          <Route path="/products/:id" element={<ProductPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/orders" element={<RequireLogin><OrdersPage /></RequireLogin>} />
          <Route path="/notifications" element={<RequireLogin><NotificationsPage /></RequireLogin>} />
          <Route path="*" element={<p>Page not found.</p>} />
        </Routes>
      </main>
    </>
  );
}

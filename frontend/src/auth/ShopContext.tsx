import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createApiClient } from '../api/client';
import { shopApi, type ShopApi } from '../api/shop';
import { readClaims } from './jwt';

export interface Session {
  token: string;
  userId: string;
  email: string;
  role: 'USER' | 'ADMIN';
  /** When the token expires, epoch milliseconds. */
  expiresAt: number;
}

interface ShopContextValue {
  api: ShopApi;
  session: Session | null;
  /** Why the user was logged out, if it was not their own choice. */
  sessionMessage: string | null;
  login: (email: string, password: string) => Promise<void>;
  logout: (message?: string) => void;
}

const ShopContext = createContext<ShopContextValue | null>(null);

/**
 * Holds the login session and the API client.
 *
 * WHERE THE TOKEN LIVES: in memory (React state), and nowhere else. JavaScript-readable storage
 * (localStorage, sessionStorage) survives a page reload, but any injected script (an XSS bug, a
 * compromised npm package) can read it and send it away. Memory is not perfectly safe from XSS
 * either, but a stolen token cannot outlive the tab. The price: reloading the page logs you out.
 * The production answer is a refresh token in an HttpOnly cookie, see the frontend lesson.
 */
export function ShopProvider({ children, fetch, initialToken }: {
  children: ReactNode;
  fetch?: typeof globalThis.fetch;
  /** Start logged in with this token (tests use it; a refresh-token flow would too). */
  initialToken?: string;
}) {
  const [session, setSession] = useState<Session | null>(() => (initialToken ? toSession(initialToken) : null));
  const [sessionMessage, setSessionMessage] = useState<string | null>(null);
  // The API client reads the token through a ref, so it is created once and always sees the latest.
  const tokenRef = useRef<string | null>(null);
  tokenRef.current = session?.token ?? null;

  const logout = useCallback((message?: string) => {
    setSession(null);
    setSessionMessage(message ?? null);
  }, []);

  const api = useMemo(() => shopApi(createApiClient({
    fetch,
    getToken: () => tokenRef.current,
    // The server refused a token we sent: it expired or was revoked. Drop it.
    onUnauthorized: () => logout('Your session has ended. Please log in again.'),
  })), [fetch, logout]);

  const login = useCallback(async (email: string, password: string) => {
    const { accessToken } = await api.login(email, password);
    setSession(toSession(accessToken));
    setSessionMessage(null);
  }, [api]);

  // Log out by ourselves when the token expires, instead of waiting for the next request to fail.
  useEffect(() => {
    if (!session) {
      return;
    }
    const timer = setTimeout(() => logout('Your session has expired. Please log in again.'),
      Math.max(0, session.expiresAt - Date.now()));
    return () => clearTimeout(timer);
  }, [session, logout]);

  const value = useMemo(() => ({ api, session, sessionMessage, login, logout }),
    [api, session, sessionMessage, login, logout]);
  return <ShopContext.Provider value={value}>{children}</ShopContext.Provider>;
}

function toSession(token: string): Session {
  const claims = readClaims(token);
  return { token, userId: claims.sub, email: claims.email, role: claims.role, expiresAt: claims.exp * 1000 };
}

export function useShop(): ShopContextValue {
  const value = useContext(ShopContext);
  if (!value) {
    throw new Error('useShop must be used inside <ShopProvider>');
  }
  return value;
}

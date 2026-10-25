/**
 * Reads the claims inside a JWT (Level 2). The middle part of a token is base64url-encoded JSON,
 * readable by anyone. This does NOT check the signature, so the result is only good for display
 * ("logged in as ..."). Every real decision is made by the server, which does check it.
 */
export interface Claims {
  sub: string;
  email: string;
  role: 'USER' | 'ADMIN';
  /** Expiry, seconds since 1970. */
  exp: number;
}

export function readClaims(token: string): Claims {
  const payload = token.split('.')[1];
  if (!payload) {
    throw new Error('Not a JWT');
  }
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(payload.length / 4) * 4, '=');
  const json = new TextDecoder().decode(Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)));
  return JSON.parse(json) as Claims;
}

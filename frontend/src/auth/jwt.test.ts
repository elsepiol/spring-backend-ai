import { describe, expect, it } from 'vitest';
import { fakeToken } from '../test/fakeApi';
import { readClaims } from './jwt';

describe('readClaims', () => {
  it('reads the claims the server put in the token', () => {
    expect(readClaims(fakeToken({ sub: '7', email: 'ada@example.com', role: 'ADMIN', exp: 1900000000 })))
      .toEqual({ sub: '7', email: 'ada@example.com', role: 'ADMIN', exp: 1900000000 });
  });

  it('copes with base64url and non-ASCII text', () => {
    // btoa cannot encode non-ASCII directly: encode to UTF-8 bytes first, then to base64url.
    const bytes = new TextEncoder().encode(JSON.stringify({ sub: '1', email: 'zoë@example.com', role: 'USER', exp: 1 }));
    const payload = btoa(String.fromCharCode(...bytes)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');

    expect(readClaims(`e30.${payload}.sig`).email).toBe('zoë@example.com');
  });

  it('rejects something that is not a JWT', () => {
    expect(() => readClaims('not-a-token')).toThrow('Not a JWT');
  });
});

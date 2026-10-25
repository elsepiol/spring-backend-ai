import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { fakeApi, fakeToken, json, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

const ordersPage = json({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });

async function fillLogin(email: string, password: string) {
  await userEvent.type(await screen.findByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
}

describe('logging in', () => {
  it('a protected page sends you to log in, and back there afterwards', async () => {
    const server = fakeApi()
      .on('POST /auth/login', json({ accessToken: fakeToken({ email: 'ada@example.com' }), tokenType: 'Bearer', expiresInSeconds: 900 }))
      .on('GET /orders', ordersPage);
    renderApp('/orders', server.fetch);

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    await fillLogin('ada@example.com', 'correct horse battery');

    expect(await screen.findByRole('heading', { name: 'Your orders' })).toBeInTheDocument();
    expect(screen.getByText('ada@example.com')).toBeInTheDocument();
    expect(server.callsTo('GET /orders')[0].headers.get('Authorization')).toMatch(/^Bearer /);
  });

  it('says the same thing for a wrong password and an unknown email', async () => {
    const server = fakeApi().on('POST /auth/login', problem(401, 'Invalid email or password'));
    renderApp('/login', server.fetch);

    await fillLogin('ada@example.com', 'wrong password!');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
  });

  it('explains a rate limit with the wait time', async () => {
    const server = fakeApi().on('POST /auth/login', problem(429, 'Too many requests', {}, { 'Retry-After': '20' }));
    renderApp('/login', server.fetch);

    await fillLogin('ada@example.com', 'guess');

    expect(await screen.findByRole('alert')).toHaveTextContent('Please wait 20 seconds');
  });
});

describe('registering', () => {
  it('shows the server\'s validation messages next to the fields', async () => {
    const server = fakeApi().on('POST /auth/register', problem(400, 'Invalid request content.',
      { errors: { email: 'must be a well-formed email address', password: 'size must be between 10 and 72' } }));
    renderApp('/register', server.fetch);

    await userEvent.type(screen.getByLabelText('Email'), 'not-an-email');
    await userEvent.type(screen.getByLabelText(/^Password/), 'short');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument();
    expect(screen.getByText('size must be between 10 and 72')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('the session', () => {
  it('ends when the server says 401 to our token', async () => {
    const server = fakeApi().on('GET /orders', problem(401, 'Authentication is required'));
    renderApp('/orders', server.fetch, { token: fakeToken() });

    expect(await screen.findByText('Your session has ended. Please log in again.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });

  it('ends by itself when the token expires, without waiting for a request to fail', async () => {
    const server = fakeApi().on('GET /orders', ordersPage);
    const expiresInAMoment = Math.floor(Date.now() / 1000) + 1;
    renderApp('/orders', server.fetch, { token: fakeToken({ exp: expiresInAMoment }) });
    expect(await screen.findByRole('heading', { name: 'Your orders' })).toBeInTheDocument();

    expect(await screen.findByText('Your session has expired. Please log in again.', {}, { timeout: 3000 }))
      .toBeInTheDocument();
  });

  it('logging out forgets the token', async () => {
    const server = fakeApi().on('GET /orders', ordersPage);
    renderApp('/orders', server.fetch, { token: fakeToken() });

    await userEvent.click(await screen.findByRole('button', { name: 'Log out' }));

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
  });
});

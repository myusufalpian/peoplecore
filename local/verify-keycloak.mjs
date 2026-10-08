import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const baseUrl = process.env.KEYCLOAK_VERIFY_URL ?? 'http://localhost:8081';
const endpoint = `${baseUrl}/realms/peoplecore-local/protocol/openid-connect/auth`;
const parameters = {
  client_id: 'peoplecore-web',
  redirect_uri: 'http://localhost:5173/callback',
  response_type: 'code',
  scope: 'openid',
  state: 'local-pkce-verification'
};

async function authorize(extra) {
  const query = new URLSearchParams({ ...parameters, ...extra });
  return fetch(`${endpoint}?${query}`, {
    redirect: 'manual',
    signal: AbortSignal.timeout(10000)
  });
}

for (const extra of [{}, { code_challenge: 'a'.repeat(43), code_challenge_method: 'plain' }]) {
  const response = await authorize(extra);
  assert.equal(response.status, 302);
  const location = new URL(response.headers.get('location'));
  assert.equal(location.searchParams.get('error'), 'invalid_request');
  assert.match(location.searchParams.get('error_description'), /code[_ ]challenge/i);
}

const challenge = createHash('sha256').update('a'.repeat(43)).digest('base64url');
const response = await authorize({ code_challenge: challenge, code_challenge_method: 'S256' });
assert.equal(response.status, 200);
assert.match(await response.text(), /kc-form-login/);
console.log('PASS: missing PKCE and plain rejected; S256 reaches login.');

import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyInvitation } from './verify-invitation.mjs';

const environment = {
  PEOPLECORE_ALLOW_LIVE_EMAIL: 'YES', PEOPLECORE_API_URL: 'http://127.0.0.1:8080',
  PEOPLECORE_EXPECTED_ISSUER: 'https://issuer.example.test', PEOPLECORE_EXPECTED_SUBJECT: 'controlled-subject',
  PEOPLECORE_OPERATOR_TOKEN: 'operator-token', PEOPLECORE_CONTROLLED_EMPLOYEE_ID: 'employee-id',
  PEOPLECORE_VERIFIED_IDENTITY_REF: 'independent-verification', PEOPLECORE_CONTROLLED_RECIPIENT: 'controlled@example.test',
};
const response = (status, body) => ({ status, json: async () => body });

test('issuance measures acceptance and never exposes or uses the API secret', async () => {
  const calls = [];
  const responses = [response(200, { data: {} }), response(201, { data: { id: 'invitation-id', secret: 'api-secret' } }),
    response(200, { data: [{ id: 'delivery-id', aggregateId: 'invitation-id', template: 'INVITATION', state: 'ACCEPTED' }] }),
    response(200, { data: [{ outcome: 'ACCEPTED', providerMessageId: 'provider-id', createdAt: '2026-11-14T00:00:00Z' }] })];
  const result = await verifyInvitation('issue', environment, { now: () => 1000, fetch: async (url, options) => {
    calls.push({ url, options }); return responses.shift();
  } });
  assert.equal(result.providerMessageId, 'provider-id'); assert.equal(result.mailboxActivationPending, true);
  assert.equal(JSON.stringify(result).includes('api-secret'), false); assert.equal(calls.length, 4);
  assert.equal(calls[0].options.redirect, 'error');
});

test('activation uses mailbox secret and proves wrong-identity denial before correct binding', async () => {
  const calls = [];
  const result = await verifyInvitation('activate', { ...environment,
    PEOPLECORE_INVITATION_MAILBOX_LINK: 'https://dummy.example.test/activate#invitationId=invitation-id&secret=mailbox-secret',
    PEOPLECORE_WRONG_IDENTITY_TOKEN: 'wrong-token', PEOPLECORE_RECIPIENT_TOKEN: 'recipient-token',
  }, { fetch: async (url, options) => {
    calls.push(options); return calls.length === 1 ? response(409, { key: 'INVITATION_CONFLICT' }) :
      response(200, { data: { employeeId: 'employee-id', issuer: environment.PEOPLECORE_EXPECTED_ISSUER, subject: environment.PEOPLECORE_EXPECTED_SUBJECT } });
  } });
  assert.equal(result.intendedIdentityActivated, true);
  assert.equal(JSON.parse(calls[1].body).secret, 'mailbox-secret');
  assert.equal(JSON.stringify(result).includes('mailbox-secret'), false);
});

test('missing explicit opt-in and unsafe endpoints fail before network calls', async () => {
  const fetch = () => { throw new Error('Unexpected network call'); };
  await assert.rejects(verifyInvitation('issue', {}, { fetch }), /opt-in/);
  await assert.rejects(verifyInvitation('issue', { ...environment, PEOPLECORE_API_URL: 'http://remote.example.test' }, { fetch }), /HTTPS/);
  await assert.rejects(verifyInvitation('issue', { ...environment, PEOPLECORE_OPERATOR_TOKEN: '' }, { fetch }), /Missing configuration/);
});

test('provider acceptance deadline fails explicitly instead of fabricating success', async () => {
  let time = 0; let calls = 0;
  await assert.rejects(verifyInvitation('issue', environment, { now: () => time,
    pause: async () => { time += 300_000; }, fetch: async () => {
      calls++; return calls === 2 ? response(201, { data: { id: 'invitation-id' } }) : response(200, { data: [] });
    } }), /within five minutes/);
});

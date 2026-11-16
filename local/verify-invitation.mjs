import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

function required(environment, name) {
  const value = environment[name];
  if (!value?.trim()) throw new Error(`Missing configuration: ${name}`);
  return value;
}

function apiUrl(value) {
  const url = new URL(value);
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash ||
      !(url.protocol === 'https:' || loopback && url.protocol === 'http:')) {
    throw new Error('Use an HTTPS API endpoint or local loopback HTTP');
  }
  return url.href.replace(/\/$/, '');
}

export async function verifyInvitation(phase, environment, dependencies = {}) {
  if (environment.PEOPLECORE_ALLOW_LIVE_EMAIL !== 'YES') throw new Error('Explicit live test opt-in is required');
  if (!['issue', 'activate'].includes(phase)) throw new Error('Choose issue or activate');
  const base = apiUrl(required(environment, 'PEOPLECORE_API_URL'));
  const fetchApi = dependencies.fetch ?? fetch;
  const now = dependencies.now ?? Date.now;
  const pause = dependencies.pause ?? delay;
  const run = randomUUID();
  const issuer = required(environment, 'PEOPLECORE_EXPECTED_ISSUER');
  const subject = required(environment, 'PEOPLECORE_EXPECTED_SUBJECT');

  async function request(path, token, method = 'GET', body, suffix = 'read', expected = 200) {
    const response = await fetchApi(`${base}${path}`, {
      method, redirect: 'error', signal: AbortSignal.timeout(10_000),
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Idempotency-Key': `${run}-${suffix}` },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (response.status !== expected) throw new Error(`API verification failed with HTTP ${response.status}`);
    return response.json();
  }

  if (phase === 'activate') {
    const link = new URL(required(environment, 'PEOPLECORE_INVITATION_MAILBOX_LINK'));
    const fragment = new URLSearchParams(link.hash.slice(1));
    const invitationId = fragment.get('invitationId');
    const secret = fragment.get('secret');
    if (!invitationId || !secret) throw new Error('Use the activation link received in the controlled mailbox');
    const payload = { invitationId, secret };
    const wrongToken = required(environment, 'PEOPLECORE_WRONG_IDENTITY_TOKEN');
    const denied = await request('/api/v1/enrollments/activations', wrongToken, 'POST', payload, 'wrong-identity', 409);
    if (denied.key !== 'INVITATION_CONFLICT') throw new Error('Wrong-identity denial did not reach invitation validation');
    const result = await request('/api/v1/enrollments/activations', required(environment, 'PEOPLECORE_RECIPIENT_TOKEN'), 'POST', payload, 'activate');
    if (result.data?.issuer !== issuer || result.data?.subject !== subject) throw new Error('Binding identity did not match');
    return { invitationId, employeeId: result.data.employeeId, intendedIdentityActivated: true, forwardedIdentityDenied: true };
  }

  const token = required(environment, 'PEOPLECORE_OPERATOR_TOKEN');
  const employeeId = required(environment, 'PEOPLECORE_CONTROLLED_EMPLOYEE_ID');
  const identityRef = required(environment, 'PEOPLECORE_VERIFIED_IDENTITY_REF');
  await request(`/api/v1/enrollments/employees/${encodeURIComponent(employeeId)}/notification-destination`, token, 'PUT', {
    address: required(environment, 'PEOPLECORE_CONTROLLED_RECIPIENT'), evidenceRef: identityRef, reason: 'Controlled provider acceptance verification',
  }, 'destination');
  const started = now();
  const issued = await request('/api/v1/enrollments/invitations', token, 'POST', {
    employeeId, intendedIdentityRef: identityRef, expectedIssuer: issuer, expectedSubject: subject,
  }, 'issue', 201);
  const invitationId = issued.data?.id;
  if (!invitationId) throw new Error('Invitation response has no public identity');
  // Activation deliberately uses the mailbox link, never the API-returned secret.
  while (now() - started < 300_000) {
    for (let page = 0; page < 10; page++) {
      const list = await request(`/api/v1/operations/notification-deliveries?page=${page}&size=100`, token);
      if (!Array.isArray(list.data)) throw new Error('Invalid delivery response');
      const delivery = list.data.find(row => row.aggregateId === invitationId && row.template === 'INVITATION');
      if (delivery && ['FAILED', 'UNKNOWN', 'SUPPRESSED'].includes(delivery.state)) throw new Error('Invitation needs operator reconciliation');
      if (delivery && ['ACCEPTED', 'DELIVERED'].includes(delivery.state)) {
        const evidence = await request(`/api/v1/operations/notification-deliveries/${delivery.id}/evidence`, token);
        const acceptance = evidence.data?.find(row => ['ACCEPTED', 'DELIVERED'].includes(row.outcome) && row.providerMessageId);
        if (acceptance && now() - started <= 300_000) {
          return { invitationId, deliveryId: delivery.id, providerMessageId: acceptance.providerMessageId,
            providerAcceptedAt: acceptance.createdAt, acceptanceElapsedMs: now() - started, mailboxActivationPending: true };
        }
      }
      if (list.data.length < 100) break;
    }
    await pause(1000);
  }
  throw new Error('Provider acceptance was not observed within five minutes');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.stdout.write(`${JSON.stringify(await verifyInvitation(process.argv[2], process.env))}\n`);
  } catch (failure) {
    const safe = failure instanceof Error && /^(Missing configuration:|Explicit live|Choose |Use |API verification|Wrong-identity|Binding identity|Invitation response|Invalid delivery|Invitation needs|Provider acceptance)/.test(failure.message);
    process.stderr.write(`${safe ? failure.message : 'Provider verification failed; sensitive response details were omitted'}\n`);
    process.exitCode = 1;
  }
}

export interface Env {
  DOCUMENTS: R2Bucket;
}

const LOCAL_ORIGIN = "http://localhost:5173";
const LOCAL_HOST = "localhost:8787";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.host !== LOCAL_HOST) {
      return new Response("Not found", {status: 404});
    }

    if (request.method === "OPTIONS") {
      return new Response(null, {headers: corsHeaders()});
    }

    if (url.pathname === "/health" && request.method === "GET") {
      return json({status: "ok"});
    }

    if (url.pathname === "/copy" && request.method === "POST") {
      if (!isTrustedMutation(request)) {
        return forbidden();
      }
      return copyObject(url, env);
    }

    if (!url.pathname.startsWith("/objects/")) {
      return new Response("Not found", {status: 404, headers: corsHeaders()});
    }

    const key = decodeURIComponent(url.pathname.substring("/objects/".length));
    if (!key) {
      return new Response("Object key is required", {status: 400, headers: corsHeaders()});
    }

    if (request.method === "PUT") {
      if (!isTrustedMutation(request)) {
        return forbidden();
      }
      await env.DOCUMENTS.put(key, request.body);
      return new Response(null, {status: 201, headers: corsHeaders()});
    }

    if (request.method === "GET") {
      const object = await env.DOCUMENTS.get(key);
      if (object == null) {
        return new Response("Not found", {status: 404, headers: corsHeaders()});
      }

      return new Response(object.body, {headers: corsHeaders()});
    }

    return new Response("Method not allowed", {status: 405, headers: corsHeaders()});
  }
} satisfies ExportedHandler<Env>;

async function copyObject(url: URL, env: Env): Promise<Response> {
  const source = url.searchParams.get("source");
  const target = url.searchParams.get("target");
  if (source == null || target == null || source.length === 0 || target.length === 0) {
    return new Response("source and target are required", {status: 400, headers: corsHeaders()});
  }

  const object = await env.DOCUMENTS.get(source);
  if (object == null) {
    return new Response("Source not found", {status: 404, headers: corsHeaders()});
  }

  await env.DOCUMENTS.put(target, object.body);
  return new Response(null, {status: 204, headers: corsHeaders()});
}

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    headers: {...corsHeaders(), "content-type": "application/json"}
  });
}

function isTrustedMutation(request: Request): boolean {
  return request.headers.get("origin") === LOCAL_ORIGIN;
}

function forbidden(): Response {
  return new Response("Forbidden", {status: 403, headers: corsHeaders()});
}

function corsHeaders(): HeadersInit {
  return {
    "access-control-allow-origin": LOCAL_ORIGIN,
    "access-control-allow-methods": "GET, PUT, POST, OPTIONS",
    "access-control-allow-headers": "content-type"
  };
}

import {describe, expect, it} from "bun:test";
import worker, {type Env} from "./index";

describe("R2 probe", () => {
  it("rejects a mutation from an untrusted origin before accessing storage", async () => {
    const bucket = new MemoryBucket();

    const response = await worker.fetch(
      new Request("http://localhost:8787/objects/staging/file.txt", {
        method: "PUT",
        headers: {origin: "https://untrusted.example"},
        body: "contents"
      }),
      environment(bucket)
    );

    expect(response.status).toBe(403);
    expect(bucket.putCalls).toBe(0);
  });

  it("rejects copy requests without a trusted origin before reading storage", async () => {
    const bucket = new MemoryBucket();
    bucket.objects.set("staging/file.txt", "contents");

    const foreignOriginResponse = await worker.fetch(
      new Request("http://localhost:8787/copy?source=staging/file.txt&target=candidate/foreign.txt", {
        method: "POST",
        headers: {origin: "https://untrusted.example"}
      }),
      environment(bucket)
    );
    const missingOriginResponse = await worker.fetch(
      new Request("http://localhost:8787/copy?source=staging/file.txt&target=candidate/missing.txt", {
        method: "POST"
      }),
      environment(bucket)
    );

    expect(foreignOriginResponse.status).toBe(403);
    expect(missingOriginResponse.status).toBe(403);
    expect(bucket.getCalls).toBe(0);
    expect(bucket.putCalls).toBe(0);
  });

  it("stores and copies a trusted browser upload", async () => {
    const bucket = new MemoryBucket();

    const putResponse = await worker.fetch(
      new Request("http://localhost:8787/objects/staging/file.txt", {
        method: "PUT",
        headers: {origin: "http://localhost:5173"},
        body: "contents"
      }),
      environment(bucket)
    );
    const copyResponse = await worker.fetch(
      new Request("http://localhost:8787/copy?source=staging/file.txt&target=candidate/file.txt", {
        method: "POST",
        headers: {origin: "http://localhost:5173"}
      }),
      environment(bucket)
    );
    const getResponse = await worker.fetch(
      new Request("http://localhost:8787/objects/candidate/file.txt"),
      environment(bucket)
    );

    expect(putResponse.status).toBe(201);
    expect(copyResponse.status).toBe(204);
    expect(getResponse.status).toBe(200);
    expect(await getResponse.text()).toBe("contents");
  });

  it("rejects a request sent to a non-local host", async () => {
    const bucket = new MemoryBucket();

    const response = await worker.fetch(
      new Request("https://peoplecore-r2-probe.example/objects/file.txt"),
      environment(bucket)
    );

    expect(response.status).toBe(404);
    expect(bucket.getCalls).toBe(0);
  });
});

function environment(bucket: MemoryBucket): Env {
  return {DOCUMENTS: bucket as unknown as R2Bucket};
}

class MemoryBucket {
  readonly objects = new Map<string, string>();
  getCalls = 0;
  putCalls = 0;

  async get(key: string): Promise<{body: ReadableStream<Uint8Array>} | null> {
    this.getCalls += 1;
    const value = this.objects.get(key);
    return value == null ? null : {body: new Blob([value]).stream()};
  }

  async put(key: string, value: BodyInit | null): Promise<void> {
    this.putCalls += 1;
    this.objects.set(key, value == null ? "" : await new Response(value).text());
  }
}

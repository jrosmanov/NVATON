import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { after, before, describe, test } from "node:test";
import { AiRefusalError, AiUnavailableError, type AiService } from "./ai.js";
import { createApp } from "./app.js";
import type { IntentRequest, ScreenRequest, VisionRequest } from "./schemas.js";
import type { SttService } from "./stt.js";
import { escapeXml, type TtsService } from "./tts.js";

class FakeAi implements AiService {
  readonly provider = "fake";
  lastIntent?: IntentRequest;
  lastVision?: VisionRequest;
  lastScreen?: ScreenRequest;
  failWith?: Error;
  configured() {
    return true;
  }
  async parseIntent(req: IntentRequest) {
    if (this.failWith) throw this.failWith;
    this.lastIntent = req;
    return {
      intent: "send_message" as const,
      contact_ids: ["c1"],
      message_text: "Salam",
      video_call: false,
      reply: "",
      language: "none" as const,
      alarm_hour: -1,
      alarm_minute: 0,
      alarm_in_minutes: 0,
      alarm_day_offset: 0,
      alarm_repeat_daily: false,
      alarm_label: "",
      alarm_ids: [],
      recipient_name: "",
      phone_number: "",
      contact_alias: "",
    };
  }
  async readImage(req: VisionRequest) {
    this.lastVision = req;
    return { readable: true, kind: "medicine" as const, title: "Paracetamol", speech: "Bu paracetamoldur.", details: [] };
  }
  async readScreen(req: ScreenRequest) {
    this.lastScreen = req;
    return { speech: "Bu, WhatsApp-dır. Murad yazıb: salam." };
  }
}

const noTts: TtsService = {
  configured: () => false,
  synthesize: async () => Buffer.alloc(0),
};

class FakeStt implements SttService {
  constructor(private readonly enabled: boolean) {}
  received = 0;
  language?: string;
  configured() {
    return this.enabled;
  }
  async transcribe(wav: Buffer, language?: string) {
    this.received = wav.length;
    this.language = language;
    return "nəvəmə yaz salam";
  }
}

function serve(ai: AiService, appToken?: string, stt: SttService = new FakeStt(false)) {
  const server = createApp({ ai, tts: noTts, stt }, { appToken }).listen(0, "127.0.0.1");
  const url = () => `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  // "listening" may fire before the before() hook runs, so check first.
  const ready = () =>
    server.listening ? Promise.resolve() : new Promise<void>((resolve) => server.once("listening", () => resolve()));
  // Drop keep-alive sockets so close() finishes immediately.
  const close = () => new Promise<void>((resolve) => {
    server.close(() => resolve());
    server.closeAllConnections();
  });
  return { url, ready, close };
}

async function post(base: string, path: string, body: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(base + path, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, json: (await res.json()) as Record<string, unknown> };
}

describe("backend API", () => {
  const ai = new FakeAi();
  const { url, ready, close } = serve(ai);
  before(ready);
  after(close);

  test("health reports configuration", async () => {
    const res = await fetch(url() + "/health");
    assert.deepEqual(await res.json(), { ok: true, ai: true, provider: "fake", tts: false, stt: false });
  });

  test("stt: not configured is a 501", async () => {
    const res = await fetch(url() + "/api/stt", { method: "POST", headers: { "content-type": "audio/wav" }, body: new Uint8Array(4000) });
    assert.equal(res.status, 501);
  });

  test("intent: valid request reaches the AI with defaults applied", async () => {
    const res = await post(url(), "/api/intent", { text: "  nəvəmə yaz salam ", contacts: [{ id: "c1", name: "Murad" }] });
    assert.equal(res.status, 200);
    assert.equal(res.json.intent, "send_message");
    assert.equal(ai.lastIntent?.text, "nəvəmə yaz salam");
    assert.deepEqual(ai.lastIntent?.contacts[0]?.aliases, []);
  });

  test("intent: missing text is a 400", async () => {
    const res = await post(url(), "/api/intent", { contacts: [] });
    assert.equal(res.status, 400);
    assert.equal(res.json.error, "bad_request");
  });

  test("intent: AI not configured is a 503", async () => {
    ai.failWith = new AiUnavailableError("no key");
    const res = await post(url(), "/api/intent", { text: "salam" });
    ai.failWith = undefined;
    assert.equal(res.status, 503);
    assert.equal(res.json.error, "claude_unavailable");
  });

  test("intent: refusal is a 422", async () => {
    ai.failWith = new AiRefusalError("bio");
    const res = await post(url(), "/api/intent", { text: "salam" });
    ai.failWith = undefined;
    assert.equal(res.status, 422);
    assert.equal(res.json.category, "bio");
  });

  test("vision: defaults the media type and forwards the question", async () => {
    const res = await post(url(), "/api/vision", { image_base64: "A".repeat(200), question: "Bu nədir?" });
    assert.equal(res.status, 200);
    assert.equal(ai.lastVision?.media_type, "image/jpeg");
    assert.equal(ai.lastVision?.question, "Bu nədir?");
    assert.deepEqual(ai.lastVision?.history, []);
  });

  test("vision: forwards earlier questions and limits how many", async () => {
    const turn = { question: "Bu nədir?", answer: "Paracetamol." };
    const ok = await post(url(), "/api/vision", { image_base64: "A".repeat(200), question: "Neçə dəfə?", history: [turn] });
    assert.equal(ok.status, 200);
    assert.deepEqual(ai.lastVision?.history, [turn]);
    const tooMany = await post(url(), "/api/vision", { image_base64: "A".repeat(200), history: Array(7).fill(turn) });
    assert.equal(tooMany.status, 400);
  });

  test("language: defaults to Azerbaijani, accepts English, rejects others", async () => {
    await post(url(), "/api/intent", { text: "salam" });
    assert.equal(ai.lastIntent?.language, "az");
    await post(url(), "/api/intent", { text: "text my grandson hello", language: "en" });
    assert.equal(ai.lastIntent?.language, "en");
    await post(url(), "/api/screen", { text: "Murad: hi", language: "en" });
    assert.equal(ai.lastScreen?.language, "en");
    const bad = await post(url(), "/api/intent", { text: "salam", language: "ru" });
    assert.equal(bad.status, 400);
  });

  test("screen: forwards the visible text, app and question", async () => {
    const res = await post(url(), "/api/screen", { text: "Murad: salam", app: "WhatsApp", question: "Kim yazıb?" });
    assert.equal(res.status, 200);
    assert.equal(res.json.speech, "Bu, WhatsApp-dır. Murad yazıb: salam.");
    assert.equal(ai.lastScreen?.app, "WhatsApp");
    assert.equal(ai.lastScreen?.question, "Kim yazıb?");
    const empty = await post(url(), "/api/screen", { text: "  " });
    assert.equal(empty.status, 400);
  });

  test("tts: not configured is a 501", async () => {
    const res = await post(url(), "/api/tts", { text: "Salam" });
    assert.equal(res.status, 501);
  });

  test("invalid JSON is a 400, not a 500", async () => {
    const res = await fetch(url() + "/api/intent", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{not json",
    });
    assert.equal(res.status, 400);
  });
});

describe("app token", () => {
  const { url, ready, close } = serve(new FakeAi(), "secret");
  before(ready);
  after(close);

  test("rejects requests without the token", async () => {
    const res = await post(url(), "/api/intent", { text: "salam" });
    assert.equal(res.status, 401);
  });

  test("accepts requests with the token", async () => {
    const res = await post(url(), "/api/intent", { text: "salam" }, { "x-app-token": "secret" });
    assert.equal(res.status, 200);
  });
});

describe("speech-to-text", () => {
  const stt = new FakeStt(true);
  const { url, ready, close } = serve(new FakeAi(), undefined, stt);
  before(ready);
  after(close);

  test("transcribes a raw WAV body", async () => {
    const res = await fetch(url() + "/api/stt", { method: "POST", headers: { "content-type": "audio/wav" }, body: new Uint8Array(4000) });
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { text: "nəvəmə yaz salam" });
    assert.equal(stt.received, 4000);
    assert.equal(stt.language, "az");
  });

  test("transcribes English when the app asks for it", async () => {
    const res = await fetch(url() + "/api/stt?lang=en", { method: "POST", headers: { "content-type": "audio/wav" }, body: new Uint8Array(4000) });
    assert.equal(res.status, 200);
    assert.equal(stt.language, "en");
  });

  test("rejects an empty body", async () => {
    const res = await fetch(url() + "/api/stt", { method: "POST", headers: { "content-type": "audio/wav" }, body: new Uint8Array(10) });
    assert.equal(res.status, 400);
  });
});

test("escapeXml escapes SSML special characters", () => {
  assert.equal(escapeXml(`<a & 'b' "c">`), "&lt;a &amp; &apos;b&apos; &quot;c&quot;&gt;");
});

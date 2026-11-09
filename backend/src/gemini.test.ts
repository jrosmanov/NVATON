import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import {
  ApiError,
  FinishReason,
  GenerateContentResponse,
  type GenerateContentParameters,
  type Part,
} from "@google/genai";
import { AiRefusalError, AiUnavailableError, AiUpstreamError } from "./ai.js";
import { GeminiService, GeminiStt, type GeminiClient } from "./gemini.js";

function reply(text: string, finishReason: FinishReason = FinishReason.STOP): GenerateContentResponse {
  const response = new GenerateContentResponse();
  response.candidates = [{ content: { role: "model", parts: [{ text }] }, finishReason }];
  return response;
}

function fakeClient(answer: () => GenerateContentResponse) {
  const calls: GenerateContentParameters[] = [];
  const client: GeminiClient = {
    models: {
      generateContent: async (params) => {
        calls.push(params);
        return answer();
      },
    },
  };
  return { client, calls };
}

function partsOf(params: GenerateContentParameters): Part[] {
  return (params.contents as { parts: Part[] }[])[0]!.parts;
}

const intentRequest = {
  text: "nəvəmə yaz salam",
  contacts: [{ id: "c1", name: "Murad", aliases: ["nəvəm"] }],
  assistant_name: "Əli",
  language: "az" as const,
  alarms: [{ id: "a1", time: "07:00", label: "", repeat_daily: false }],
};

const NO_EXTRAS = { language: "none", alarm_hour: -1, alarm_minute: 0, alarm_in_minutes: 0, alarm_day_offset: 0, alarm_repeat_daily: false, alarm_label: "", alarm_ids: [], recipient_name: "", phone_number: "", contact_alias: "" };

beforeEach(() => {
  delete process.env.GEMINI_API_KEY;
});

test("intent: asks for JSON with a schema and drops unknown contact ids", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ intent: "send_message", contact_ids: ["c1", "ghost"], message_text: "Salam", video_call: false, reply: "", ...NO_EXTRAS })),
  );
  const result = await new GeminiService(client).parseIntent(intentRequest);
  assert.deepEqual(result.contact_ids, ["c1"]);
  assert.equal(result.message_text, "Salam");
  assert.equal(calls[0]!.config?.responseMimeType, "application/json");
  assert.ok(calls[0]!.config?.responseSchema);
});

test("intent: a correction to a draft message comes back as edit_message, with the draft in the prompt", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ intent: "edit_message", contact_ids: [], message_text: "Axşam gələcəm", video_call: false, reply: "", ...NO_EXTRAS })),
  );
  const draft = "A WhatsApp message to Murad was read back to the user for confirmation: «Axşam gəlirəm». The user is now correcting it.";
  const result = await new GeminiService(client).parseIntent({ ...intentRequest, text: "yox, gələcəm yaz", context: draft });
  assert.equal(result.intent, "edit_message");
  assert.equal(result.message_text, "Axşam gələcəm");
  const prompt = partsOf(calls[0]!).map((p) => p.text).join("\n");
  assert.ok(prompt.includes("«Axşam gəlirəm»"));
  assert.ok(String(calls[0]!.config?.systemInstruction).includes("edit_message"));
  const schema = calls[0]!.config?.responseSchema as { properties: { intent: { enum: string[] } } };
  assert.ok(schema.properties.intent.enum.includes("edit_message"));
});

test("intent: a voice message for someone outside the contacts keeps their name", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ intent: "voice_message", contact_ids: [], message_text: "", video_call: false, reply: "", ...NO_EXTRAS, recipient_name: "Əziz" })),
  );
  const result = await new GeminiService(client).parseIntent({ ...intentRequest, text: "Əzizə səsli mesaj göndərmək istəyirəm" });
  assert.equal(result.intent, "voice_message");
  assert.equal(result.recipient_name, "Əziz");
  const schema = calls[0]!.config?.responseSchema as { properties: { intent: { enum: string[] } } };
  assert.ok(schema.properties.intent.enum.includes("voice_message"));
  assert.ok(String(calls[0]!.config?.systemInstruction).includes("voice_message"));
});

test("vision: sends the photo inline with the question", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ readable: true, kind: "medicine", title: "Paracetamol", speech: "Bu paracetamoldur.", details: [] })),
  );
  await new GeminiService(client).readImage({ image_base64: "QUJD", media_type: "image/jpeg", question: "Bu nədir?", history: [], language: "az" });
  const parts = partsOf(calls[0]!);
  assert.equal(parts[0]!.inlineData?.mimeType, "image/jpeg");
  assert.equal(parts[1]!.text, "Question: Bu nədir?");
});

test("vision: earlier questions come before the new one, so follow-ups make sense", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ readable: true, kind: "medicine", title: "Paracetamol", speech: "Qutuda yazılmayıb.", details: [] })),
  );
  await new GeminiService(client).readImage({
    image_base64: "QUJD",
    media_type: "image/jpeg",
    question: "Bəs uşaqlara olar?",
    history: [{ question: "Bu nə dərmandır?", answer: "Bu Paracetamol 500 mq-dır." }],
    language: "az",
  });
  const text = partsOf(calls[0]!)[1]!.text!;
  assert.match(text, /Question: Bu nə dərmandır\?\nAnswer: Bu Paracetamol 500 mq-dır\./);
  assert.ok(text.endsWith("Question: Bəs uşaqlara olar?"));
  assert.ok(text.indexOf("Paracetamol") < text.indexOf("Bəs uşaqlara"));
});

test("vision: without a question it asks what the item is", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ readable: true, kind: "other", title: "Çaynik", speech: "Bu çaynikdir.", details: [] })),
  );
  await new GeminiService(client).readImage({ image_base64: "QUJD", media_type: "image/jpeg", history: [], language: "az" });
  assert.equal(partsOf(calls[0]!)[1]!.text, "Question: Bu nədir? Üzərində nə yazılıb?");
});

test("an overloaded model (503) is retried once with the other model", async () => {
  let attempt = 0;
  const { client, calls } = fakeClient(() => {
    attempt++;
    if (attempt === 1) throw new ApiError({ message: "This model is currently experiencing high demand.", status: 503 });
    return reply(JSON.stringify({ readable: true, kind: "other", title: "Otaq", speech: "Bu otaqdır.", details: [] }));
  });
  const result = await new GeminiService(client).readImage({ image_base64: "QUJD", media_type: "image/jpeg", history: [], language: "az" });
  assert.equal(result.title, "Otaq");
  assert.equal(calls.length, 2);
  assert.notEqual(calls[0]!.model, calls[1]!.model);
});

test("other API errors are not retried", async () => {
  const { client, calls } = fakeClient(() => {
    throw new ApiError({ message: "Invalid argument", status: 400 });
  });
  await assert.rejects(new GeminiService(client).parseIntent(intentRequest), AiUpstreamError);
  assert.equal(calls.length, 1);
});

test("a safety stop becomes a refusal", async () => {
  const { client } = fakeClient(() => reply("", FinishReason.SAFETY));
  await assert.rejects(new GeminiService(client).parseIntent(intentRequest), AiRefusalError);
});

test("an answer that is not valid JSON is an upstream error", async () => {
  const { client } = fakeClient(() => reply("not json"));
  await assert.rejects(new GeminiService(client).parseIntent(intentRequest), AiUpstreamError);
});

test("an answer in the wrong shape is an upstream error", async () => {
  const { client } = fakeClient(() => reply(JSON.stringify({ intent: "dance" })));
  await assert.rejects(new GeminiService(client).parseIntent(intentRequest), AiUpstreamError);
});

test("a rejected key means the AI is unavailable", async () => {
  const client: GeminiClient = {
    models: {
      generateContent: async () => {
        throw new ApiError({ message: "API key not valid. Please pass a valid API key.", status: 400 });
      },
    },
  };
  await assert.rejects(new GeminiService(client).parseIntent(intentRequest), AiUnavailableError);
});

test("no key configured means unavailable, without calling the API", async () => {
  const gemini = new GeminiService();
  assert.equal(gemini.configured(), false);
  await assert.rejects(gemini.parseIntent(intentRequest), AiUnavailableError);
});

test("alarms: the phone's clock, alarms and context reach the model; unknown alarm ids are dropped", async () => {
  const { client, calls } = fakeClient(() =>
    reply(JSON.stringify({ ...NO_EXTRAS, intent: "cancel_alarms", contact_ids: [], message_text: "", video_call: false, reply: "", alarm_ids: ["a1", "ghost"] })),
  );
  const result = await new GeminiService(client).parseIntent({
    ...intentRequest,
    text: "7-dəki zəngli saatı sil",
    now: "2026-11-08 17:05, Sunday",
    context: "Murad: axşam gələcəyəm",
  });
  assert.equal(result.intent, "cancel_alarms");
  assert.deepEqual(result.alarm_ids, ["a1"]);
  const text = partsOf(calls[0]!)[0]!.text!;
  assert.match(text, /Alarms already set \(JSON\): \[\{"id":"a1","time":"07:00"/);
  assert.match(text, /current local time: 2026-11-08 17:05, Sunday/);
  assert.match(text, /just read to the user[^«]*«Murad: axşam gələcəyəm»/);
  assert.match(JSON.stringify(calls[0]!.config?.systemInstruction), /set_alarm/);
});

test("English: prompts ask for English answers and default questions", async () => {
  const { client, calls } = fakeClient(() => reply(JSON.stringify({ intent: "chat", contact_ids: [], message_text: "", video_call: false, reply: "Hi!", ...NO_EXTRAS })));
  const gemini = new GeminiService(client);
  await gemini.parseIntent({ ...intentRequest, text: "text my grandson hello", language: "en" });
  const system = JSON.stringify(calls[0]!.config?.systemInstruction);
  assert.match(system, /reply: English/);
  assert.match(system, /nəvəm = my grandchild/);
  await assert.rejects(gemini.readImage({ image_base64: "QUJD", media_type: "image/jpeg", history: [], language: "en" }));
  assert.equal(partsOf(calls[1]!)[1]!.text, "Question: What is this? What does it say?");
  assert.match(JSON.stringify(calls[1]!.config?.systemInstruction), /always answer in English/);
});

test("speech: sends WAV audio and returns a clean transcript", async () => {
  const { client, calls } = fakeClient(() => reply(" «Nəvəmə yaz salam» \n"));
  const text = await new GeminiStt(new GeminiService(client)).transcribe(Buffer.alloc(2000));
  assert.equal(text, "Nəvəmə yaz salam");
  assert.equal(partsOf(calls[0]!)[0]!.inlineData?.mimeType, "audio/wav");
});

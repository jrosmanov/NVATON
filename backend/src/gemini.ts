import {
  ApiError,
  FinishReason,
  GoogleGenAI,
  Type,
  type GenerateContentParameters,
  type GenerateContentResponse,
  type Part,
  type Schema,
} from "@google/genai";
import type { z } from "zod";
import {
  AiRateLimitError,
  AiRefusalError,
  AiUnavailableError,
  AiUpstreamError,
  type AiService,
} from "./ai.js";
import {
  intentSystemPrompt,
  intentUserText,
  screenSystemPrompt,
  screenUserText,
  transcribePrompt,
  visionSystemPrompt,
  visionUserText,
} from "./prompts.js";
import {
  INTENTS,
  IntentResult,
  ScreenResult,
  VisionResult,
  type IntentRequest,
  type Language,
  type ScreenRequest,
  type VisionRequest,
} from "./schemas.js";
import { SttError, type SttService } from "./stt.js";

/** The slice of the Google Gen AI SDK this service uses; tests pass a fake. */
export interface GeminiClient {
  models: { generateContent(params: GenerateContentParameters): Promise<GenerateContentResponse> };
}

// Response schemas in Gemini's structured-output format. They mirror the Zod schemas in
// schemas.ts, which still validate every answer before it reaches the app.
const INTENT_FIELDS = [
  "intent",
  "contact_ids",
  "message_text",
  "video_call",
  "reply",
  "language",
  "alarm_hour",
  "alarm_minute",
  "alarm_in_minutes",
  "alarm_day_offset",
  "alarm_repeat_daily",
  "alarm_label",
  "alarm_ids",
  "recipient_name",
  "phone_number",
  "contact_alias",
];

const INTENT_SCHEMA: Schema = {
  type: Type.OBJECT,
  properties: {
    intent: { type: Type.STRING, enum: [...INTENTS] },
    contact_ids: { type: Type.ARRAY, items: { type: Type.STRING } },
    message_text: { type: Type.STRING },
    video_call: { type: Type.BOOLEAN },
    reply: { type: Type.STRING },
    language: { type: Type.STRING, enum: ["none", "az", "en"] },
    alarm_hour: { type: Type.INTEGER },
    alarm_minute: { type: Type.INTEGER },
    alarm_in_minutes: { type: Type.INTEGER },
    alarm_day_offset: { type: Type.INTEGER },
    alarm_repeat_daily: { type: Type.BOOLEAN },
    alarm_label: { type: Type.STRING },
    alarm_ids: { type: Type.ARRAY, items: { type: Type.STRING } },
    recipient_name: { type: Type.STRING },
    phone_number: { type: Type.STRING },
    contact_alias: { type: Type.STRING },
  },
  required: INTENT_FIELDS,
  propertyOrdering: INTENT_FIELDS,
};

const VISION_SCHEMA: Schema = {
  type: Type.OBJECT,
  properties: {
    readable: { type: Type.BOOLEAN },
    kind: { type: Type.STRING, enum: ["medicine", "document", "bill", "other"] },
    title: { type: Type.STRING },
    speech: { type: Type.STRING },
    details: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        properties: { label: { type: Type.STRING }, value: { type: Type.STRING } },
        required: ["label", "value"],
      },
    },
  },
  required: ["readable", "kind", "title", "speech", "details"],
  propertyOrdering: ["readable", "kind", "title", "speech", "details"],
};

const SCREEN_SCHEMA: Schema = {
  type: Type.OBJECT,
  properties: { speech: { type: Type.STRING } },
  required: ["speech"],
};

const BLOCKED_FINISH: ReadonlySet<string> = new Set([
  FinishReason.SAFETY,
  FinishReason.PROHIBITED_CONTENT,
  FinishReason.BLOCKLIST,
  FinishReason.SPII,
  FinishReason.IMAGE_SAFETY,
  FinishReason.RECITATION,
]);

/** Overloaded or briefly failing on Google's side: worth one try with the other model. */
const RETRYABLE_STATUS: ReadonlySet<number> = new Set([500, 503, 504]);

/** Upper bounds per request, so a slow or overloaded model can't hang the app (it times out at 60 s). */
const TEXT_TIMEOUT_MS = 25_000;
const VISION_TIMEOUT_MS = 40_000;

export class GeminiService implements AiService {
  readonly provider = "gemini";
  // Pinned, stable (v1 API) models, picked by measured latency on 2026-11-07:
  // flash-lite answered spoken commands in about 1.5 s; the bigger Flash reads small print better
  // (about 3 s). gemini-3.8-flash took over 75 s on the free tier. The "-latest" aliases exist
  // only on the v1beta API, which this SDK does not use.
  private readonly model = process.env.GEMINI_MODEL || "gemini-3.5-flash-lite";
  private readonly visionModel = process.env.GEMINI_VISION_MODEL || "gemini-3.6-flash";
  private client: GeminiClient | null;

  constructor(client?: GeminiClient) {
    this.client = client ?? null;
  }

  configured(): boolean {
    return this.client !== null || Boolean(process.env.GEMINI_API_KEY);
  }

  private getClient(): GeminiClient {
    if (this.client) return this.client;
    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) throw new AiUnavailableError("GEMINI_API_KEY is not set (backend/.env)");
    this.client = new GoogleGenAI({ apiKey });
    return this.client;
  }

  /**
   * One request; returns the answer text. With a schema, Gemini must answer in that JSON shape.
   * When the model is overloaded ("high demand", 503) the other model answers instead: both read images.
   */
  async generate(system: string, parts: Part[], schema?: Schema, vision = false): Promise<string> {
    const primary = vision ? this.visionModel : this.model;
    const fallback = vision ? this.model : this.visionModel;
    let response: GenerateContentResponse;
    try {
      response = await this.request(primary, system, parts, schema, vision);
    } catch (err) {
      if (!(err instanceof ApiError && RETRYABLE_STATUS.has(err.status)) || fallback === primary) throw toAiError(err);
      console.warn(`Gemini ${primary} unavailable (${err.status}); trying ${fallback}`);
      try {
        response = await this.request(fallback, system, parts, schema, vision);
      } catch (retryErr) {
        throw toAiError(retryErr);
      }
    }
    const blockReason = response.promptFeedback?.blockReason;
    if (blockReason) throw new AiRefusalError(String(blockReason));
    const finish = response.candidates?.[0]?.finishReason;
    if (finish && BLOCKED_FINISH.has(finish)) throw new AiRefusalError(String(finish));
    const text = response.text;
    if (text === undefined) throw new AiUpstreamError(`Gemini returned no text (finishReason=${finish ?? "none"})`);
    return text;
  }

  private request(model: string, system: string, parts: Part[], schema: Schema | undefined, vision: boolean) {
    return this.getClient().models.generateContent({
      model,
      contents: [{ role: "user", parts }],
      config: {
        systemInstruction: system,
        httpOptions: { timeout: vision ? VISION_TIMEOUT_MS : TEXT_TIMEOUT_MS },
        ...(schema ? { responseMimeType: "application/json", responseSchema: schema } : {}),
      },
    });
  }

  private async structured<S extends z.ZodType>(
    system: string,
    parts: Part[],
    schema: Schema,
    validator: S,
    vision = false,
  ): Promise<z.output<S>> {
    const text = await this.generate(system, parts, schema, vision);
    let data: unknown;
    try {
      data = JSON.parse(text);
    } catch {
      throw new AiUpstreamError("Gemini returned invalid JSON");
    }
    const parsed = validator.safeParse(data);
    if (!parsed.success) throw new AiUpstreamError("Gemini's answer did not match the expected format");
    return parsed.data;
  }

  async parseIntent(req: IntentRequest): Promise<IntentResult> {
    const result = await this.structured(
      intentSystemPrompt(req.assistant_name || "Əli", req.language),
      [{ text: intentUserText(req) }],
      INTENT_SCHEMA,
      IntentResult,
    );
    return keepKnownIds(result, req);
  }

  async readImage(req: VisionRequest): Promise<VisionResult> {
    return this.structured(
      visionSystemPrompt(new Date().toISOString().slice(0, 10), req.language),
      [
        { inlineData: { mimeType: req.media_type, data: req.image_base64 } },
        { text: visionUserText(req.question, req.history ?? [], req.language) },
      ],
      VISION_SCHEMA,
      VisionResult,
      true,
    );
  }

  async readScreen(req: ScreenRequest): Promise<ScreenResult> {
    return this.structured(
      screenSystemPrompt(req.language),
      [{ text: screenUserText(req.app, req.question, req.text, req.language) }],
      SCREEN_SCHEMA,
      ScreenResult,
    );
  }
}

/** Speech recognition with Gemini (Azerbaijani or English), so one Gemini key covers voice input too. */
export class GeminiStt implements SttService {
  constructor(private readonly gemini: GeminiService) {}

  configured(): boolean {
    return this.gemini.configured();
  }

  async transcribe(wav: Buffer, language: Language = "az"): Promise<string> {
    try {
      const text = await this.gemini.generate(transcribePrompt(language), [
        { inlineData: { mimeType: "audio/wav", data: wav.toString("base64") } },
        { text: "Transcribe this recording." },
      ]);
      return text.trim().replace(/^["«“']+|["»”']+$/g, "").trim();
    } catch (err) {
      if (err instanceof AiRefusalError) return "";
      throw new SttError(`Gemini speech recognition failed: ${(err as Error).message}`);
    }
  }
}

/** The model may only pick contacts and alarms the app sent. */
export function keepKnownIds(result: IntentResult, req: IntentRequest): IntentResult {
  const contacts = new Set(req.contacts.map((c) => c.id));
  const alarms = new Set(req.alarms.map((a) => a.id));
  return {
    ...result,
    contact_ids: result.contact_ids.filter((id) => contacts.has(id)),
    alarm_ids: result.alarm_ids.filter((id) => alarms.has(id)),
  };
}

function toAiError(err: unknown): Error {
  if (err instanceof AiUnavailableError) return err;
  if (err instanceof ApiError) {
    // Google answers a bad key with 400 "API key not valid" and a disabled API with 403.
    if (err.status === 401 || err.status === 403 || (err.status === 400 && /api[ _]?key/i.test(err.message))) {
      return new AiUnavailableError("Gemini rejected the API key; check GEMINI_API_KEY");
    }
    if (err.status === 429) return new AiRateLimitError("Gemini rate limit or free-tier quota reached");
    return new AiUpstreamError(`Gemini API error ${err.status}: ${err.message.slice(0, 200)}`);
  }
  return new AiUpstreamError(`Cannot reach the Gemini API: ${err instanceof Error ? err.message : String(err)}`);
}

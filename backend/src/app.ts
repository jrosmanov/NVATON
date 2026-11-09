import express, { type NextFunction, type Request, type Response } from "express";
import { ZodError } from "zod";
import {
  AiRateLimitError,
  AiRefusalError,
  AiUnavailableError,
  AiUpstreamError,
  type AiService,
} from "./ai.js";
import { IntentRequest, ScreenRequest, TtsRequest, VisionRequest } from "./schemas.js";
import { SttError, type SttService } from "./stt.js";
import { TtsError, type TtsService } from "./tts.js";

export interface Services {
  ai: AiService;
  tts: TtsService;
  stt: SttService;
}

export interface AppOptions {
  /** When set, every /api request must send it in the X-App-Token header. */
  appToken?: string;
}

export function createApp({ ai, tts, stt }: Services, options: AppOptions = {}) {
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "15mb" }));

  app.use((req, res, next) => {
    const started = Date.now();
    res.on("finish", () => {
      console.log(`${req.method} ${req.originalUrl} → ${res.statusCode} (${Date.now() - started} ms)`);
    });
    next();
  });

  app.get("/health", (_req, res) => {
    res.json({ ok: true, ai: ai.configured(), provider: ai.provider, tts: tts.configured(), stt: stt.configured() });
  });

  app.use("/api", (req, res, next) => {
    if (options.appToken && req.get("x-app-token") !== options.appToken) {
      res.status(401).json({ error: "unauthorized" });
      return;
    }
    next();
  });

  // Express 5 forwards rejected promises from async handlers to the error handler.
  app.post("/api/intent", async (req, res) => {
    res.json(await ai.parseIntent(IntentRequest.parse(req.body)));
  });

  app.post("/api/vision", async (req, res) => {
    res.json(await ai.readImage(VisionRequest.parse(req.body)));
  });

  app.post("/api/screen", async (req, res) => {
    res.json(await ai.readScreen(ScreenRequest.parse(req.body)));
  });

  app.post("/api/tts", async (req, res) => {
    if (!tts.configured()) {
      res.status(501).json({ error: "tts_not_configured" });
      return;
    }
    const { text } = TtsRequest.parse(req.body);
    res.type("audio/mpeg").send(await tts.synthesize(text));
  });

  // Raw WAV body (16 kHz mono PCM) → { text }. Fallback for phones without Azerbaijani speech recognition.
  app.post("/api/stt", express.raw({ type: () => true, limit: "5mb" }), async (req, res) => {
    if (!stt.configured()) {
      res.status(501).json({ error: "stt_not_configured" });
      return;
    }
    const audio: unknown = req.body;
    if (!Buffer.isBuffer(audio) || audio.length < 1000) {
      res.status(400).json({ error: "bad_request", message: "Send a WAV file as the request body" });
      return;
    }
    const language = req.query.lang === "en" ? "en" : "az";
    res.json({ text: await stt.transcribe(audio, language) });
  });

  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    if (err instanceof ZodError) {
      res.status(400).json({ error: "bad_request", issues: err.issues });
    } else if (err instanceof AiUnavailableError) {
      res.status(503).json({ error: "claude_unavailable", message: err.message });
    } else if (err instanceof AiRefusalError) {
      res.status(422).json({ error: "refused", category: err.message });
    } else if (err instanceof AiRateLimitError) {
      res.status(429).json({ error: "rate_limited" });
    } else if (err instanceof AiUpstreamError || err instanceof TtsError || err instanceof SttError) {
      console.error(err.message);
      res.status(502).json({ error: "upstream_error", message: err.message });
    } else {
      // body-parser errors (invalid JSON, payload too large) carry a 4xx status.
      const status = (err as { status?: number }).status;
      if (typeof status === "number" && status >= 400 && status < 500) {
        res.status(status).json({ error: "bad_request" });
      } else {
        console.error(err);
        res.status(500).json({ error: "internal_error" });
      }
    }
  });

  return app;
}

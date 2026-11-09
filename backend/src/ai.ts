import type {
  IntentRequest,
  IntentResult,
  ScreenRequest,
  ScreenResult,
  VisionRequest,
  VisionResult,
} from "./schemas.js";

/** What the HTTP layer needs from the AI provider. Lets tests swap in a fake. */
export interface AiService {
  /** "gemini" or "claude", shown in /health and in the app's settings. */
  readonly provider: string;
  configured(): boolean;
  parseIntent(req: IntentRequest): Promise<IntentResult>;
  readImage(req: VisionRequest): Promise<VisionResult>;
  readScreen(req: ScreenRequest): Promise<ScreenResult>;
}

/** No API key, or the key was rejected → the app falls back to its local rules. */
export class AiUnavailableError extends Error {}
/** Claude declined the request (stop_reason "refusal") even after the server-side fallback. */
export class AiRefusalError extends Error {}
export class AiRateLimitError extends Error {}
/** Claude API returned an error or could not be reached. */
export class AiUpstreamError extends Error {}

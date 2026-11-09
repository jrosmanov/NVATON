import Anthropic from "@anthropic-ai/sdk";
import { betaZodOutputFormat } from "@anthropic-ai/sdk/helpers/beta/zod";
import type { z } from "zod";
import {
  AiRateLimitError,
  AiRefusalError,
  AiUnavailableError,
  AiUpstreamError,
  type AiService,
} from "./ai.js";
import { intentSystemPrompt, intentUserText, screenSystemPrompt, screenUserText, visionSystemPrompt, visionUserText } from "./prompts.js";
import {
  IntentResult,
  ScreenResult,
  VisionResult,
  type IntentRequest,
  type ScreenRequest,
  type VisionRequest,
} from "./schemas.js";
import { keepKnownIds } from "./gemini.js";

// Server-side refusal fallback: if a safety classifier declines a request, the API
// re-runs it on the model Anthropic recommends for that refusal category.
const FALLBACK_BETA = "server-side-fallback-2026-07-01";

type Effort = "low" | "medium" | "high";

export class ClaudeService implements AiService {
  readonly provider = "claude";
  private client: Anthropic | null = null;
  // Read env lazily (in the constructor) so values from .env, loaded at startup, apply.
  private readonly model = process.env.CLAUDE_MODEL || "claude-opus-5-5";

  configured(): boolean {
    return Boolean(process.env.ANTHROPIC_API_KEY || process.env.ANTHROPIC_AUTH_TOKEN);
  }

  private getClient(): Anthropic {
    if (!this.configured()) {
      throw new AiUnavailableError("ANTHROPIC_API_KEY is not set (backend/.env)");
    }
    // Default constructor resolves ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN from the environment.
    this.client ??= new Anthropic();
    return this.client;
  }

  private async structured<S extends z.ZodType>(opts: {
    system: string;
    content: string | Anthropic.Beta.BetaContentBlockParam[];
    schema: S;
    effort: Effort;
    maxTokens: number;
  }): Promise<z.output<S>> {
    const client = this.getClient();
    let response;
    try {
      response = await client.beta.messages.parse({
        model: this.model,
        max_tokens: opts.maxTokens,
        betas: [FALLBACK_BETA],
        fallbacks: "default",
        system: opts.system,
        output_config: { effort: opts.effort, format: betaZodOutputFormat(opts.schema) },
        messages: [{ role: "user", content: opts.content }],
      });
    } catch (err) {
      // Most specific first; connection errors before the generic APIError.
      if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError) {
        throw new AiUnavailableError("Claude rejected the API key; check ANTHROPIC_API_KEY");
      }
      if (err instanceof Anthropic.RateLimitError) throw new AiRateLimitError("Claude rate limit");
      if (err instanceof Anthropic.BadRequestError) {
        throw new AiUpstreamError(`Claude rejected the request: ${err.message}`);
      }
      if (err instanceof Anthropic.APIConnectionError) throw new AiUpstreamError("Cannot reach the Claude API");
      if (err instanceof Anthropic.APIError) throw new AiUpstreamError(`Claude API error ${err.status}`);
      throw err;
    }

    // Check stop_reason before reading content: a refusal has no usable output.
    if (response.stop_reason === "refusal") {
      throw new AiRefusalError(response.stop_details?.category ?? "refusal");
    }
    if (response.parsed_output == null) {
      throw new AiUpstreamError(`Claude returned no parsable output (stop_reason=${response.stop_reason})`);
    }
    return response.parsed_output as z.output<S>;
  }

  async parseIntent(req: IntentRequest): Promise<IntentResult> {
    const result = await this.structured({
      system: intentSystemPrompt(req.assistant_name || "Əli", req.language),
      content: intentUserText(req),
      schema: IntentResult,
      effort: "low", // latency matters more than depth for a spoken command
      maxTokens: 2048,
    });
    return keepKnownIds(result, req);
  }

  async readImage(req: VisionRequest): Promise<VisionResult> {
    return this.structured({
      system: visionSystemPrompt(new Date().toISOString().slice(0, 10), req.language),
      content: [
        { type: "image", source: { type: "base64", media_type: req.media_type, data: req.image_base64 } },
        { type: "text", text: visionUserText(req.question, req.history ?? [], req.language) },
      ],
      schema: VisionResult,
      effort: "medium", // reading small print accurately is worth a little more thought
      maxTokens: 4096,
    });
  }

  async readScreen(req: ScreenRequest): Promise<ScreenResult> {
    return this.structured({
      system: screenSystemPrompt(req.language),
      content: screenUserText(req.app, req.question, req.text, req.language),
      schema: ScreenResult,
      effort: "low",
      maxTokens: 2048,
    });
  }
}

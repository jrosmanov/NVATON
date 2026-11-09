import type { AiService } from "./ai.js";
import { createApp } from "./app.js";
import { ClaudeService } from "./claude.js";
import { GeminiService, GeminiStt } from "./gemini.js";
import { AzureStt, type SttService } from "./stt.js";
import { AzureTts } from "./tts.js";

try {
  process.loadEnvFile(".env");
} catch {
  // No .env file: rely on variables already set in the environment.
}

const port = Number(process.env.PORT) || 8787;
// 127.0.0.1 by default: reachable from the phone through `adb reverse` only.
// Set HOST=0.0.0.0 (and APP_TOKEN) to accept phones on the same Wi-Fi.
const host = process.env.HOST || "127.0.0.1";

/** AI_PROVIDER=gemini|claude wins; otherwise Gemini, unless only an Anthropic key is set. */
function selectProvider(): "gemini" | "claude" {
  const forced = process.env.AI_PROVIDER?.trim().toLowerCase();
  if (forced === "gemini" || forced === "claude") return forced;
  return process.env.ANTHROPIC_API_KEY && !process.env.GEMINI_API_KEY ? "claude" : "gemini";
}

const ai: AiService = selectProvider() === "claude" ? new ClaudeService() : new GeminiService();
const tts = new AzureTts();
// Speech recognition: Azure's dedicated az-AZ model when configured, else Gemini.
const azureStt = new AzureStt();
const stt: SttService = azureStt.configured() || !(ai instanceof GeminiService) ? azureStt : new GeminiStt(ai);
const app = createApp({ ai, tts, stt }, { appToken: process.env.APP_TOKEN || undefined });

app.listen(port, host, () => {
  const keyName = ai.provider === "claude" ? "ANTHROPIC_API_KEY" : "GEMINI_API_KEY";
  const sttName = stt === azureStt ? "Azure (az-AZ)" : "Gemini";
  console.log(`Köməkçi backend: http://${host}:${port}`);
  console.log(`  AI (${ai.provider}): ${ai.configured() ? "ready" : `NOT configured (set ${keyName} in .env)`}`);
  console.log(`  Speech recognition: ${stt.configured() ? sttName : "off (the app relies on the phone's speech recognition)"}`);
  console.log(`  Azure TTS: ${tts.configured() ? "ready" : "off (the app will use on-device TTS)"}`);
  if (host !== "127.0.0.1" && !process.env.APP_TOKEN) {
    console.warn("  Warning: listening on the network without APP_TOKEN; anyone on this Wi-Fi can use your API key.");
  }
});

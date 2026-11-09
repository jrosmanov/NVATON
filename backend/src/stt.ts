// Optional Azure Speech speech-to-text (az-AZ). Many phones' built-in recognizer has no
// Azerbaijani language pack; then the app records audio and sends it here instead.
// Uses the same AZURE_SPEECH_KEY / AZURE_SPEECH_REGION as text-to-speech.

import type { Language } from "./schemas.js";

export interface SttService {
  configured(): boolean;
  /** 16 kHz mono 16-bit PCM WAV in, recognised text out ("" when nothing was understood). */
  transcribe(wav: Buffer, language?: Language): Promise<string>;
}

export class SttError extends Error {}

export class AzureStt implements SttService {
  private readonly key = process.env.AZURE_SPEECH_KEY ?? "";
  private readonly region = process.env.AZURE_SPEECH_REGION ?? "";
  private readonly language = process.env.AZURE_STT_LANGUAGE || "az-AZ";

  configured(): boolean {
    return Boolean(this.key) && /^[a-z0-9]+$/.test(this.region) && /^[A-Za-z]{2,3}-[A-Za-z]{2,4}$/.test(this.language);
  }

  async transcribe(wav: Buffer, language: Language = "az"): Promise<string> {
    if (!this.configured()) throw new SttError("Azure STT is not configured");
    const url =
      `https://${this.region}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1` +
      `?language=${language === "en" ? "en-US" : this.language}&format=simple`;
    let res: Response;
    try {
      res = await fetch(url, {
        method: "POST",
        headers: {
          "Ocp-Apim-Subscription-Key": this.key,
          "Content-Type": "audio/wav; codecs=audio/pcm; samplerate=16000",
          Accept: "application/json",
        },
        body: new Uint8Array(wav),
        signal: AbortSignal.timeout(20_000),
      });
    } catch (err) {
      throw new SttError(`Azure STT unreachable: ${(err as Error).message}`);
    }
    if (!res.ok) throw new SttError(`Azure STT failed with HTTP ${res.status}`);
    const data = (await res.json()) as { RecognitionStatus?: string; DisplayText?: string };
    // NoMatch / InitialSilenceTimeout are normal "didn't catch that" outcomes, not errors.
    return data.RecognitionStatus === "Success" ? (data.DisplayText ?? "").trim() : "";
  }
}

// Optional Azure Speech text-to-speech with a native Azerbaijani neural voice.
// Android's built-in TTS usually has no Azerbaijani voice, so this makes "Əli"
// sound natural. Without AZURE_SPEECH_KEY the app falls back to on-device TTS.

export interface TtsService {
  configured(): boolean;
  synthesize(text: string): Promise<Buffer>;
}

export class TtsError extends Error {}

export function escapeXml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

export class AzureTts implements TtsService {
  private readonly key = process.env.AZURE_SPEECH_KEY ?? "";
  private readonly region = process.env.AZURE_SPEECH_REGION ?? "";
  private readonly voice = process.env.AZURE_SPEECH_VOICE || "az-AZ-BanuNeural"; // or az-AZ-BabekNeural

  configured(): boolean {
    return Boolean(this.key) && /^[a-z0-9]+$/.test(this.region) && /^[A-Za-z0-9-]+$/.test(this.voice);
  }

  async synthesize(text: string): Promise<Buffer> {
    if (!this.configured()) throw new TtsError("Azure TTS is not configured");
    // A slightly slower rate is easier for elderly listeners.
    const ssml =
      `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="az-AZ">` +
      `<voice name="${this.voice}"><prosody rate="-8%">${escapeXml(text)}</prosody></voice></speak>`;
    let res: Response;
    try {
      res = await fetch(`https://${this.region}.tts.speech.microsoft.com/cognitiveservices/v1`, {
        method: "POST",
        headers: {
          "Ocp-Apim-Subscription-Key": this.key,
          "Content-Type": "application/ssml+xml",
          "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
          "User-Agent": "komekci-backend",
        },
        body: ssml,
        signal: AbortSignal.timeout(15_000),
      });
    } catch (err) {
      throw new TtsError(`Azure TTS unreachable: ${(err as Error).message}`);
    }
    if (!res.ok) throw new TtsError(`Azure TTS failed with HTTP ${res.status}`);
    return Buffer.from(await res.arrayBuffer());
  }
}

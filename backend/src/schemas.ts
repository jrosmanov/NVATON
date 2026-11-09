import { z } from "zod";

// ---------- Requests coming from the Android app ----------

/** The language the user chose in the app: what they speak and what every spoken answer must be in. */
export const Language = z.enum(["az", "en"]).default("az");
export type Language = z.output<typeof Language>;

export const Contact = z.object({
  id: z.string().min(1).max(64),
  name: z.string().min(1).max(80),
  aliases: z.array(z.string().max(40)).max(10).default([]),
});

/** An alarm the app has already set, so "cancel the 7 o'clock alarm" can name it. */
export const AlarmInfo = z.object({
  id: z.string().min(1).max(64),
  time: z.string().max(20), // "07:30"
  label: z.string().max(100).default(""),
  repeat_daily: z.boolean().default(false),
});

export const IntentRequest = z.object({
  text: z.string().trim().min(1).max(500),
  contacts: z.array(Contact).max(50).default([]),
  assistant_name: z.string().trim().max(40).optional(),
  language: Language,
  alarms: z.array(AlarmInfo).max(30).default([]),
  /** The phone's local date and time, e.g. "2026-11-08 17:05, Sunday", to resolve "tomorrow" or "in half an hour". */
  now: z.string().max(60).optional(),
  /** What the assistant just read to the user (a screen or messages), so follow-up questions make sense. */
  context: z.string().max(6000).optional(),
});
export type IntentRequest = z.output<typeof IntentRequest>;

/** One earlier question about the same item and the answer that was read aloud. */
export const VisionTurn = z.object({
  question: z.string().trim().max(500),
  answer: z.string().trim().max(1500),
});
export type VisionTurn = z.output<typeof VisionTurn>;

export const VisionRequest = z.object({
  image_base64: z.string().min(100).max(15_000_000),
  media_type: z.enum(["image/jpeg", "image/png", "image/webp"]).default("image/jpeg"),
  question: z.string().trim().max(500).optional(),
  /** Earlier questions on the same camera screen, oldest first, so follow-ups like "bəs uşaqlara olar?" make sense. */
  history: z.array(VisionTurn).max(6).default([]),
  language: Language,
});
export type VisionRequest = z.output<typeof VisionRequest>;

export const ScreenRequest = z.object({
  /** Visible text of the app the user was looking at, collected on the phone (password fields are skipped). */
  text: z.string().trim().min(1).max(6000),
  /** The app's name, e.g. "WhatsApp". */
  app: z.string().trim().max(100).optional(),
  question: z.string().trim().max(500).optional(),
  language: Language,
});
export type ScreenRequest = z.output<typeof ScreenRequest>;

export const TtsRequest = z.object({
  text: z.string().trim().min(1).max(1500),
});

// ---------- Structured outputs Claude must return ----------
// Keep these to plain objects, strings, booleans, enums and arrays so they map
// cleanly onto structured outputs (output_config.format).

export const INTENTS = [
  "send_message",
  "edit_message",
  "call",
  "read_camera",
  "read_screen",
  "read_messages",
  "open_chat",
  "voice_message",
  "save_contact",
  "import_contacts",
  "set_alarm",
  "list_alarms",
  "cancel_alarms",
  "set_language",
  "chat",
  "unknown",
] as const;

export const IntentResult = z.object({
  intent: z.enum(INTENTS),
  contact_ids: z.array(z.string()),
  message_text: z.string(),
  video_call: z.boolean(),
  reply: z.string(),
  /** set_language: "az" or "en"; otherwise "none" (Gemini's schema allows no empty enum value). */
  language: z.enum(["none", "az", "en"]),
  /** set_alarm: a clock time (24 h) or a delay in minutes; -1 / 0 when not used. */
  alarm_hour: z.number().int(),
  alarm_minute: z.number().int(),
  alarm_in_minutes: z.number().int(),
  /** 0 = the next time this clock time comes, 1 = tomorrow, 2 = the day after... */
  alarm_day_offset: z.number().int(),
  alarm_repeat_daily: z.boolean(),
  alarm_label: z.string(),
  /** cancel_alarms: ids from the request's alarm list; empty with intent cancel_alarms means all of them. */
  alarm_ids: z.array(z.string()),
  /** send_message / call / open_chat to someone not in the contacts list: their name as said, without case endings. */
  recipient_name: z.string(),
  /** save_contact: the number as digits only ("0501234567"), spoken number words converted; "" if not said. */
  phone_number: z.string(),
  /** save_contact: a relation word if they said one ("nəvəm", "qızım"), else "". */
  contact_alias: z.string(),
});
export type IntentResult = z.output<typeof IntentResult>;

export const VisionResult = z.object({
  readable: z.boolean(),
  kind: z.enum(["medicine", "document", "bill", "other"]),
  title: z.string(),
  speech: z.string(),
  details: z.array(z.object({ label: z.string(), value: z.string() })),
});
export type VisionResult = z.output<typeof VisionResult>;

export const ScreenResult = z.object({
  speech: z.string(),
});
export type ScreenResult = z.output<typeof ScreenResult>;

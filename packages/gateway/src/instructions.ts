import { activeProfile } from './apps/index.js';
/** The active application's agent instructions (QuPath's live in apps/qupath-instructions.ts, Fiji's in apps/fiji-instructions.ts). */
export const instructions = activeProfile().instructions;

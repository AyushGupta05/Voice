/** Instant routes for a few unambiguous spoken view commands. A route matches only the whole
 * utterance, only targets display-only (or auto-approved undo) tools, and never invents parameters; anything
 * else goes to the agent. Each application defines its own routes in `apps/`. */
import { activeProfile, type AppProfile } from './apps/index.js';
export type { FastRoute } from './apps/profile.js';

export function fastRoute(text: string, context: unknown, profile: AppProfile = activeProfile()) { return profile.fastRoute(text, context); }

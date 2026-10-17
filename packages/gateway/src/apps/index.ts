import { qupath } from './qupath.js';
import { fiji } from './fiji.js';
import { fidelis } from './fidelis.js';
import type { AppId, AppProfile } from './profile.js';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
export type { AppId, AppProfile, FastRoute, PrepareInput } from './profile.js';

/** Every application Penny can drive, by id. */
export const profiles: Record<AppId, AppProfile> = { qupath, fiji, fidelis };
export const appIds = Object.keys(profiles) as AppId[];
/** LUMA_APP selects the application. The companion starts its gateway with `open -n`, where the shell environment does
 * not reach, so when the variable is unset and a root is known (LUMA_ROOT, as the companion and launchers set it) the
 * launcher-written `<root>/.local/state/app` file ("qupath", "fiji" or "fidelis") is read. With neither, QuPath. Test processes set
 * no LUMA_ROOT, so the suite never follows whichever application was launched last. */
export function selectApp(setting: string | undefined = process.env.LUMA_APP, root: string | undefined = process.env.LUMA_ROOT): AppId {
  let value = setting?.trim() ?? '';
  let source = 'LUMA_APP';
  if (value === '' && root) { source = '.local/state/app'; try { value = readFileSync(resolve(root, '.local/state/app'), 'utf8').trim().toLowerCase(); } catch { value = ''; } }
  if (value === '') return 'qupath';
  if ((appIds as string[]).includes(value)) return value as AppId;
  throw new Error(`${source} must be one of ${appIds.map(id => `"${id}"`).join(', ')}, not "${value}".`);
}
const active = profiles[selectApp()];
/** The profile this process runs with. Modules that need another profile (tests, tool-reference generation) take it as a parameter. */
export function activeProfile(): AppProfile { return active; }
/** Which profile a tool name belongs to, from its prefix; undefined for unknown names. */
export function profileForTool(name: string): AppProfile | undefined { return appIds.map(id => profiles[id]).find(profile => name.startsWith(profile.toolPrefix + '_')); }

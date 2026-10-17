import type { Tool } from './tools.js';
import { toolFactory } from './tool-schema.js';
import { desktopOperation } from './desktop-bridge.js';

const text = { type: 'string' };
const number = { type: 'number' };

/** What the desktop tools need to know about the application they complement. */
export type DesktopApp = {
  /** Tool-name prefix (`qupath` → `qupath_desktop_inspect`). */
  toolPrefix: string;
  /** macOS bundle id brought forward by default. */
  bundleId: string;
  displayName: string;
  /** The in-app UI toolkit the `<prefix>_ui` tools can see, e.g. "JavaFX" or "AWT/Swing". */
  uiToolkit: string;
  /** Example of a native sheet the in-app tools cannot see, quoted in guidance. */
  nativeDialogExample: string;
};

/**
 * App-agnostic macOS Accessibility tools, answered by the companion rather than the Java bridge. Both profiles
 * include them under their own prefix; the default bundle id is the profile's application.
 */
export function desktopToolsFor(app: DesktopApp): Tool[] {
  const tool = toolFactory(app.toolPrefix);
  const p = app.toolPrefix;
  const WHEN = `Use the in-app ${p}_ui / ${p}_ui_action first; they see ${app.uiToolkit} controls directly. Use the desktop tools only for what they cannot see: OS file open/save dialogs, ${app.nativeDialogExample}, third-party plugin windows, alerts and any other window on screen.`;
  const bundle = { type: 'string', maxLength: 200, description: `Bundle id to bring forward first: "${app.bundleId}" (default), "frontmost" or "none".` };
  const label = { type: 'string', maxLength: 80, description: 'Two to six words shown beside Luma’s cursor while it flies to the target.' };
  return [
    tool('desktop_inspect', `List the windows and actionable elements of ${app.displayName} (default), the frontmost app or any bundle ids via macOS Accessibility, including OS-owned open/save panels and alerts. Each element has a stable id, role, title/label/value, enabled and a global screen frame (top-left origin). ${WHEN} Always inspect immediately before acting: ids expire after 30 s. Reports trusted:false when Luma lacks the Accessibility permission, which only the researcher can grant.`, desktopOperation('inspect'), 'read', {
      apps: { type: 'array', items: { type: 'string', maxLength: 200 }, maxItems: 8, description: `Bundle ids, or "frontmost". Default ["${app.bundleId}"].` },
      windowTitleContains: { type: 'string', maxLength: 200 },
      maxElements: { type: 'integer', minimum: 1, maximum: 2000 },
    }),
    tool('desktop_act', `Operate one element id from the latest ${p}_desktop_inspect (within 30 s): press a button/menu item, toggle a checkbox, focus or select a row/tab/option, setValue or type into a field. Accessibility actions are tried first; otherwise Luma moves the real mouse to the element and clicks or types. The cursor flies there first. ${WHEN} Re-inspect afterwards; dispatch is not completion. Never bypass disabled controls.`, desktopOperation('act'), 'code', {
      id: { type: 'string', minLength: 1, maxLength: 200 },
      action: { type: 'string', enum: ['press', 'setValue', 'focus', 'select', 'toggle', 'type'] },
      value: { type: 'string', maxLength: 4096, description: 'Text for setValue/type.' },
      label,
    }, ['id', 'action']),
    tool('desktop_type', `Type text into whatever currently has keyboard focus (${app.displayName} is brought forward first), optionally pressing Return after. For a file dialog: ${p}_desktop_key ["cmd+shift+g"], then ${p}_desktop_type the absolute path with submit true, then ${p}_desktop_key ["return"] to open. ${WHEN}`, desktopOperation('type'), 'code', {
      text: { type: 'string', minLength: 1, maxLength: 4096 },
      submit: { type: 'boolean', description: 'Press Return after typing.' },
      app: bundle, label,
    }, ['text']),
    tool('desktop_key', `Press keyboard shortcuts in order, e.g. ["cmd+shift+g"] to open the path sheet in a file chooser, ["return"], ["escape"], ["tab"], ["cmd+o"]. Modifiers cmd/shift/alt/ctrl plus one key name (letters, digits, return, escape, tab, space, delete, arrows, f1-f12). ${WHEN}`, desktopOperation('key'), 'code', {
      keys: { type: 'array', items: { type: 'string', minLength: 1, maxLength: 40 }, minItems: 1, maxItems: 10 },
      app: bundle, label,
    }, ['keys']),
    tool('desktop_click', `Click at exact global screen coordinates (top-left origin, from a frame returned by ${p}_desktop_inspect) when no element id fits, e.g. a canvas or custom-drawn plugin control. Luma flies its cursor there, then moves the real mouse and clicks. ${WHEN} Prefer ${p}_desktop_act with an id whenever one exists.`, desktopOperation('click'), 'code', {
      x: number, y: number,
      count: { type: 'integer', minimum: 1, maximum: 3, description: '2 for a double-click.' },
      button: { type: 'string', enum: ['left', 'right'] },
      modifiers: { type: 'array', items: { type: 'string', enum: ['cmd', 'shift', 'alt', 'ctrl'] }, maxItems: 4 },
      app: bundle, label,
    }, ['x', 'y']),
  ];
}

/** QuPath's desktop tools (the original definitions; kept as the QuPath profile's and for existing imports). */
export const QUPATH_DESKTOP_APP: DesktopApp = { toolPrefix: 'qupath', bundleId: 'science.luma.qupath', displayName: 'QuPath', uiToolkit: 'JavaFX', nativeDialogExample: '"Set image type"-style native sheets' };
export const desktopTools: Tool[] = desktopToolsFor(QUPATH_DESKTOP_APP);
/** Names of all QuPath desktop tools, for routing checks. */
export const desktopToolNames = new Set(desktopTools.map(tool => tool.name));
export { text };

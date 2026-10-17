/** Instant routes for a few unambiguous spoken view commands. A route matches only the whole
 * utterance, only targets display-only tools, and never touches data; anything else goes to the agent. */
export type FastRoute = { tool: string; arguments: Record<string, unknown>; reply: string };

const clean = (text: string) => text.toLowerCase().replace(/[^a-z' ]+/g, ' ').replace(/\s+/g, ' ').trim()
  .replace(/^(?:(?:hey )?luma |okay |ok |please |can you |could you )+/, '').replace(/ please$/, '').trim();

export function fastRoute(text: string, context: unknown): FastRoute | undefined {
  const said = clean(text);
  const viewer = (context as { viewer?: { centerX?: unknown; centerY?: unknown; downsample?: unknown }; image?: unknown } | undefined);
  const zoom = said.match(/^zoom (in|out)(?: (a (?:little|bit)|slightly|a lot|way (?:in|out)|more))?$/);
  if (zoom) {
    const x = Number(viewer?.viewer?.centerX), y = Number(viewer?.viewer?.centerY), current = Number(viewer?.viewer?.downsample);
    if (!viewer?.image || ![x, y, current].every(Number.isFinite) || current <= 0) return undefined;
    const factor = /lot|way/.test(zoom[2] ?? '') ? 4 : 2;
    const next = Math.min(256, Math.max(0.05, zoom[1] === 'in' ? current / factor : current * factor));
    if (Math.abs(next - current) < 1e-9) return undefined;
    return { tool: 'qupath_focus', arguments: { x, y, downsample: next }, reply: zoom[1] === 'in' ? 'Zooming in.' : 'Zooming out.' };
  }
  if (!viewer?.image) return undefined;
  const toggle = said.match(/^(show|hide|turn (?:on|off)) (?:the |all )?(cells|detections|annotations)$/);
  if (toggle) {
    const on = toggle[1] === 'show' || toggle[1] === 'turn on';
    const key = toggle[2] === 'annotations' ? 'annotations' : 'detections';
    return { tool: 'qupath_overlays', arguments: { [key]: on }, reply: `${on ? 'Showing' : 'Hiding'} ${toggle[2]}.` };
  }
  const fill = said.match(/^(fill|unfill|outline) (?:the )?(cells|detections)$/);
  if (fill) return { tool: 'qupath_overlays', arguments: { detections: true, fill: fill[1] === 'fill' }, reply: fill[1] === 'fill' ? 'Filling cells.' : 'Showing outlines.' };
  return undefined;
}

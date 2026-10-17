import type { FastRoute } from './profile.js';
import { finite } from './profile.js';

type ImageContext = { image?: { id?: unknown; width?: unknown; height?: unknown; calibration?: { unit?: unknown; pixelWidth?: unknown; pixelHeight?: unknown } }; counts?: { roiManager?: unknown; roiManagerForImage?: unknown }; roi?: unknown; viewer?: { srcRect?: { x?: unknown; y?: unknown; width?: unknown; height?: unknown } } };
export function imageObjectCount(context: unknown): number {
  const counts = (context as ImageContext | undefined)?.counts;
  // A global ROI Manager can still contain another image's detections.
  return finite(counts?.roiManagerForImage) ?? finite(counts?.roiManager) ?? 0;
}

const ones = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const tens = ['twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];
/** Bounded spoken quantities, including decimals, rather than deleting punctuation from numeric input. */
export function spokenQuantity(value: string): number | undefined {
  const text = value.toLowerCase().replace(/-/g, ' ').replace(/\s+/g, ' ').trim();
  if (/^\d+(?:\.\d+)?$/.test(text)) return finite(Number(text));
  const small = (part: string): number | undefined => {
    const single = ones.indexOf(part); if (single >= 0) return single;
    const [ten, one, extra] = part.split(' '), decade = tens.indexOf(ten), digit = ones.indexOf(one);
    return decade >= 0 && extra === undefined && (one === undefined || digit > 0 && digit < 10) ? (decade + 2) * 10 + (one === undefined ? 0 : digit) : undefined;
  };
  const [integer, decimals, extra] = text.split(' point '); if (extra !== undefined) return undefined;
  const hundred = integer.match(/^([a-z]+) hundred(?: (?:and )?(.+))?$/);
  let whole = small(integer);
  if (hundred) {
    const digit = ones.indexOf(hundred[1]), rest = hundred[2] === undefined ? 0 : small(hundred[2]);
    if (digit < 1 || digit > 9 || rest === undefined) return undefined;
    whole = digit * 100 + rest;
  }
  if (whole === undefined) return undefined;
  if (decimals === undefined) return whole;
  const digits = decimals.split(' ').map(part => ones.indexOf(part));
  if (!digits.length || digits.length > 6 || digits.some(digit => digit < 0 || digit > 9)) return undefined;
  return Number(`${whole}.${digits.join('')}`);
}

const lengthInMicrons = (unit: string) => /^(?:µm|μm|um|microns?|micromet(?:er|re)s?)$/i.test(unit) ? 1
  : /^(?:nm|nanomet(?:er|re)s?)$/i.test(unit) ? 0.001
  : /^(?:mm|millimet(?:er|re)s?)$/i.test(unit) ? 1000 : undefined;

/** Explicit area corrections preserve the requested unit; an uncalibrated image cannot supply square microns. */
export function areaCorrection(text: string, context: unknown): FastRoute | undefined {
  if (imageObjectCount(context) <= 0) return undefined;
  const said = text.toLowerCase().replace(/^(?:(?:hey )?(?:fiji|penny)[, ]+|please |can you |could you )+/, '').replace(/[.!?]+$/, '').trim();
  const match = said.match(/^(?:remove|drop|delete|exclude|filter out|get rid of) (?:anything|everything|(?:all )?(?:the )?(?:cells|objects|nuclei|detections|particles)(?: that are)?) (?:smaller than|less than|under|below) (.+?) (square (?:microns?|micrometres?|micrometers?|pixels?)|(?:µm|μm|um)(?:²|2|\^2)|microns? squared|pixels? squared)(?: please)?$/);
  if (!match) return undefined;
  const amount = spokenQuantity(match[1]); if (amount === undefined || amount < 0) return undefined;
  const calibration = (context as ImageContext).image?.calibration;
  const unit = typeof calibration?.unit === 'string' ? calibration.unit : 'pixel';
  const pixels = /pixel/.test(match[2]);
  const scale = lengthInMicrons(unit);
  let area: number | undefined;
  if (pixels) {
    const width = finite(calibration?.pixelWidth), height = finite(calibration?.pixelHeight);
    area = /^pixels?$/i.test(unit) ? amount : width !== undefined && width > 0 && height !== undefined && height > 0 ? amount * width * height : undefined;
  } else if (scale !== undefined) area = amount / (scale * scale);
  const route: FastRoute = { tool: 'fiji_filter_objects', arguments: {}, reply: `Removing anything under ${amount}.` };
  if (area === undefined || !Number.isFinite(area)) return { ...route, refusal: pixels ? 'The image has no usable pixel calibration.' : 'This image has no physical scale. Set its pixel size before filtering in square microns.' };
  return { ...route, arguments: { minArea: area } };
}

/** Count existing objects in the indicated region without replacing the segmentation or changing the selection. */
export function regionCount(text: string, context: unknown): FastRoute | undefined {
  if (!(context as ImageContext | undefined)?.image) return undefined;
  const said = text.toLowerCase().replace(/[?!.,]/g, '').replace(/-/g, ' ').replace(/\s+/g, ' ').trim()
    .replace(/^(?:(?:hey )?(?:fiji|penny) |please |can you |could you )+/, '');
  const match = said.match(/^(?:how many(?: (?:cells|objects|nuclei|particles))?(?: are)?(?: there)?|count(?: (?:the |these )?(?:cells|objects|nuclei|particles))?) (?:only )?(?:in|inside|within) (?:the |this |that )?(selected region|selection|region|area|box|top left|top right|bottom left|bottom right|middle|centre|center)(?: (?:corner|region|area))?(?: please)?$/);
  if (!match) return undefined;
  const where = match[1];
  const route: FastRoute = { tool: 'fiji_object_stats', arguments: { plot: false, scope: 'selection' }, reply: 'Counting in that region.' };
  const state = context as ImageContext;
  const selected = /^(?:selected region|selection|region|area|box)$/.test(where);
  if (selected && (state.roi || /^(?:selected region|selection)$/.test(where))) return route;
  const image = state.image, width = finite(image?.width), height = finite(image?.height);
  if (width === undefined || height === undefined || width <= 0 || height <= 0) return undefined;
  // Match the profile's model defaults: corners/middle are thirds of the current visible rectangle.
  // With no selection, "this region" means its central quarter by area. Clamp zoomed-out viewports to real pixels.
  const visible = state.viewer?.srcRect;
  const vx = finite(visible?.x) ?? 0, vy = finite(visible?.y) ?? 0;
  const left = Math.max(0, Math.min(width, vx)), top = Math.max(0, Math.min(height, vy));
  const right = Math.max(left, Math.min(width, vx + (finite(visible?.width) ?? width)));
  const bottom = Math.max(top, Math.min(height, vy + (finite(visible?.height) ?? height)));
  if (right <= left || bottom <= top) return undefined;
  const w = right - left, h = bottom - top, centre = /^(?:middle|centre|center)$/.test(where);
  const region = selected ? { x: left + w / 4, y: top + h / 4, width: w / 2, height: h / 2 }
    : centre ? { x: left + w / 3, y: top + h / 3, width: w / 3, height: h / 3 }
      : { x: left + (/right/.test(where) ? w * 2 / 3 : 0), y: top + (/bottom/.test(where) ? h * 2 / 3 : 0), width: w / 3, height: h / 3 };
  return { ...route, arguments: { plot: false, region } };
}

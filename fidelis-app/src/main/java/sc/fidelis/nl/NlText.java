package sc.fidelis.nl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text normalisation for the deterministic interpreter (docs D08 section 2.1): lower-case, punctuation
 * and filler removal, number words to numerals (including decimals), unit canonicalisation and
 * sequence splitting. Pure functions; no engine access.
 *
 * <p>Canonical unit tokens in normalised text: {@code um}, {@code nm}, {@code mm}, {@code cm},
 * {@code px}, and {@code um2} for areas in square micrometres.
 */
final class NlText {
    private NlText() {}

    private static final Map<String, Integer> ONES = new HashMap<>();
    private static final Map<String, Integer> TENS = new HashMap<>();

    static {
        String[] ones = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < ones.length; i++) ONES.put(ones[i], i);
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 2; i < tens.length; i++) TENS.put(tens[i], i * 10);
    }

    private static final Pattern FILLER = Pattern.compile(
            "^(?:(?:hey |ok |okay )?fidelis |please |kindly |could you |can you |would you |will you |i want to |i would like to |"
                    + "i d like to |i need to |i want you to |let s |lets |go ahead and |now |just )+");

    private static final Pattern SQUARE_UNITS = Pattern.compile(
            "(?:square\\s+(?:micro(?:n|metre|meter)s?|um|µm|μm)|"
                    + "(?:micro(?:n|metre|meter)s?|um|µm|μm)\\s*(?:squared|\\^2|²|2)(?![\\d.]))");
    private static final Pattern SQUARE_PIXELS = Pattern.compile("(?:square\\s+(?:pixels?|px)|(?:pixels?|px)\\s*(?:squared|\\^2|²))");

    /** Full normalisation of one instruction. */
    static String normalise(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        s = s.replace('’', ' ').replace('\'', ' ');
        s = SQUARE_UNITS.matcher(s).replaceAll(" um2 ");
        s = SQUARE_PIXELS.matcher(s).replaceAll(" px ");
        s = s.replaceAll("\\bmicro(?:n|metre|meter)s?\\b", " um ")
                .replaceAll("(?<![a-z])[µμ]m\\b", " um ")
                .replaceAll("\\bnano(?:metre|meter)s?\\b", " nm ")
                .replaceAll("\\bmilli(?:metre|meter)s?\\b", " mm ")
                .replaceAll("\\bcenti(?:metre|meter)s?\\b", " cm ")
                .replaceAll("\\bpixels?\\b", " px ");
        // number-unit glue: "2um", "3px", "0.5nm"
        s = s.replaceAll("(\\d)(um2|um|nm|mm|cm|px)\\b", "$1 $2");
        // numeric ranges and hyphens
        s = s.replaceAll("(?<=\\d)\\s*-\\s*(?=\\d)", " to ");
        s = s.replaceAll("[-_/]", " ");
        // periods only survive between digits
        s = s.replaceAll("(?<!\\d)\\.|\\.(?!\\d)", " ");
        s = s.replaceAll("[,;:!?\"()\\[\\]{}]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        s = numberWords(s);
        s = s.replaceAll("\\s+", " ").trim();
        String before;
        do {
            before = s;
            s = FILLER.matcher(s).replaceFirst("");
        } while (!s.equals(before));
        return s.trim();
    }

    /** Convert spoken numbers ("twenty five", "two point five") to numerals. */
    static String numberWords(String s) {
        String[] t = s.split(" ");
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < t.length) {
            String w = t[i];
            if (isNumberWord(w)) {
                int total = 0;
                int current = 0;
                int last = 0; // 0 none, 1 ones 0-9, 2 teens, 3 tens, 4 hundred/thousand
                int j = i;
                while (j < t.length) {
                    String x = t[j];
                    Integer one = ONES.get(x);
                    if (one != null && one < 10 && (last == 0 || last == 3 || last == 4)) {
                        current += one;
                        last = 1;
                    } else if (one != null && one >= 10 && (last == 0 || last == 4)) {
                        current += one;
                        last = 2;
                    } else if (TENS.containsKey(x) && (last == 0 || last == 4)) {
                        current += TENS.get(x);
                        last = 3;
                    } else if (x.equals("hundred") && last >= 1 && last <= 3) {
                        current = current * 100;
                        last = 4;
                    } else if (x.equals("thousand") && last >= 1) {
                        total += current * 1000;
                        current = 0;
                        last = 4;
                    } else if (x.equals("and") && last == 4 && j + 1 < t.length && isNumberWord(t[j + 1])) {
                        // "one hundred and twenty"
                    } else {
                        break;
                    }
                    j++;
                }
                if (j == i) {
                    out.add(w);
                    i++;
                    continue;
                }
                String num = Integer.toString(total + current);
                i = j;
                i = decimalTail(t, i, out, num);
                continue;
            }
            if (w.equals("point") && !out.isEmpty() && out.get(out.size() - 1).matches("\\d+") && i + 1 < t.length
                    && (t[i + 1].matches("\\d+") || ONES.containsKey(t[i + 1]))) {
                String prev = out.remove(out.size() - 1);
                i = decimalTail(t, i, out, prev);
                continue;
            }
            out.add(w);
            i++;
        }
        return String.join(" ", out);
    }

    /** Handle "<num> point <digits...>" starting at t[i]; appends the number to out; returns the new index. */
    private static int decimalTail(String[] t, int i, List<String> out, String integerPart) {
        if (i < t.length && t[i].equals("point") && i + 1 < t.length
                && (ONES.containsKey(t[i + 1]) && ONES.get(t[i + 1]) < 10 || t[i + 1].matches("\\d+"))) {
            StringBuilder frac = new StringBuilder();
            int j = i + 1;
            while (j < t.length) {
                if (ONES.containsKey(t[j]) && ONES.get(t[j]) < 10) frac.append(ONES.get(t[j]));
                else if (t[j].matches("\\d+") && frac.length() == 0) frac.append(t[j]);
                else break;
                j++;
            }
            out.add(integerPart + "." + frac);
            return j;
        }
        out.add(integerPart);
        return i;
    }

    private static boolean isNumberWord(String w) {
        return ONES.containsKey(w) || TENS.containsKey(w) || w.equals("hundred") || w.equals("thousand");
    }

    private static final String VERBS = "(?:invert|blur|gaussian|median|subtract|remove|threshold|auto threshold|convert|"
            + "watershed|measure|set measurements|analy[sz]e|count|duplicate|undo|redo|select|clear|enhance|stretch|"
            + "apply|z project|project|find|make|8 bit|16 bit|32 bit|mean filter|smooth|erode|dilate|fill holes|"
            + "deselect|crop|subtract background)\\b";
    private static final Pattern SPLIT = Pattern.compile(
            " (?:and then|then|after that|afterwards|after which|followed by|next) | and (?=" + VERBS + ")");

    /** Split a normalised instruction into sequential clauses. */
    static List<String> splitSteps(String normalised) {
        String s = normalised;
        for (String lead : new String[] {"then ", "and then ", "and ", "first "}) {
            if (s.startsWith(lead)) s = s.substring(lead.length());
        }
        List<String> out = new ArrayList<>();
        for (String c : SPLIT.split(s)) {
            c = c.trim();
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    // ---- quantities -----------------------------------------------------------------------------

    /** A number with an optional canonical unit token ({@code um nm mm cm px um2}), or null unit. */
    record Quantity(double value, String unit) {}

    private static final String NUM = "(\\d+(?:\\.\\d+)?)";
    private static final String UNIT = "(um2|um|nm|mm|cm|px)";

    /**
     * Find a quantity: first {@code <key> [of|is|at|to|=] <n> [unit]}, then {@code <n> [unit] <key>},
     * then any {@code <n> <unit>}; with {@code allowBare} a lone bare number.
     */
    static Quantity quantity(String c, String keyRegex, boolean allowBare) {
        Matcher m = Pattern.compile("(?:" + keyRegex + ")(?: size| value| of| is| at| to| equals| =| by| with| for)* " + NUM
                + "(?: " + UNIT + "\\b)?").matcher(c);
        if (m.find()) return new Quantity(Double.parseDouble(m.group(1)), m.group(2));
        m = Pattern.compile(NUM + "(?: " + UNIT + "\\b)? (?:" + keyRegex + ")\\b").matcher(c);
        if (m.find()) return new Quantity(Double.parseDouble(m.group(1)), m.group(2));
        m = Pattern.compile(NUM + " " + UNIT + "\\b").matcher(c);
        if (m.find()) return new Quantity(Double.parseDouble(m.group(1)), m.group(2));
        if (allowBare) {
            m = Pattern.compile("(?<![\\w.])" + NUM + "(?![\\w.])").matcher(c);
            if (m.find()) {
                Quantity q = new Quantity(Double.parseDouble(m.group(1)), null);
                if (!m.find()) return q; // exactly one bare number
            }
        }
        return null;
    }

    /** Millimetre-free unit factor to metres, or -1 if the unit name is not a length unit. */
    static double metresPer(String unit) {
        if (unit == null) return -1;
        switch (unit.toLowerCase(Locale.ROOT).replace("μ", "µ")) {
            case "um": case "µm": case "micron": case "microns": case "micrometer": case "micrometre":
                return 1e-6;
            case "nm": return 1e-9;
            case "mm": return 1e-3;
            case "cm": return 1e-2;
            case "m": return 1.0;
            default: return -1;
        }
    }

    static boolean containsAny(String s, String... phrases) {
        for (String p : phrases) if (Pattern.compile("\\b" + Pattern.quote(p) + "\\b").matcher(s).find()) return true;
        return false;
    }

    static boolean matches(String s, String regex) {
        return Pattern.compile(regex).matcher(s).find();
    }

    static List<String> words(String s) {
        return Arrays.asList(s.split(" "));
    }
}

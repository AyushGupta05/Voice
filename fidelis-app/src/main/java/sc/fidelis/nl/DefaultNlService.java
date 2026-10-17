package sc.fidelis.nl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.ParameterType;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.NlText.Quantity;

/**
 * Deterministic natural-language interpreter (docs D08 section 2, A17; ADR-007). Front end A is a
 * controlled grammar with synonyms for the demonstration command set; front end B retrieves from the
 * engine's catalogue and fills slots from the schema. No language model is involved, and nothing is
 * executed: the result is a {@link Proposal} for review.
 *
 * <p>Policy (deterministic): a consequential slot (see {@link #policyNote()}) is filled only from the
 * user's words or derived from them by a stated rule (unit conversion, document facts); never from a
 * remembered or default value. Missing or ambiguous slots become precise questions. Requests contain
 * every key of the command's schema. This class never produces {@code CODE} requests.
 */
public final class DefaultNlService implements NlService {

    /** AutoThresholder method names (docs A17 section 4). */
    static final List<String> THRESHOLD_METHODS = List.of("Default", "Huang", "Intermodes", "IsoData", "IJ_IsoData",
            "Li", "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy",
            "Shanbhag", "Triangle", "Yen");

    private static final Map<String, String> METHOD_SPOKEN = new LinkedHashMap<>();

    static {
        METHOD_SPOKEN.put("ij isodata", "IJ_IsoData");
        METHOD_SPOKEN.put("ij iso data", "IJ_IsoData");
        METHOD_SPOKEN.put("max entropy", "MaxEntropy");
        METHOD_SPOKEN.put("maximum entropy", "MaxEntropy");
        METHOD_SPOKEN.put("renyi entropy", "RenyiEntropy");
        METHOD_SPOKEN.put("min error", "MinError");
        METHOD_SPOKEN.put("iso data", "IsoData");
        for (String m : THRESHOLD_METHODS) METHOD_SPOKEN.putIfAbsent(m.toLowerCase(Locale.ROOT), m);
    }

    private static final Pattern DENY = Pattern.compile("\\b(?:files?|folders?|director(?:y|ies)|disk|drive|hard drive|system|"
            + "registry|passwords?|network|internet|email|e mail|shell|command prompt|cmd|powershell|bash|exec|execute|"
            + "eval|script|scripts|macro|macros|code|python|java|groovy|javascript|jython|runtime|process builder|"
            + "download|upload|install|uninstall|shutdown|reboot|format|sudo|rm|del)\\b");

    private static final Set<String> STOP = Set.copyOf(new LinkedHashSet<>(Arrays.asList("the", "a", "an", "this", "that", "image", "it", "to", "of", "on", "with",
            "please", "and", "for", "in", "my", "me", "current", "all", "now", "use", "using", "do", "run", "apply", "by", "at",
            "from", "into", "then", "is", "are", "be", "show", "make", "give", "get", "want", "need", "would", "like", "can",
            "could", "you", "i", "we", "as", "so", "up", "down", "out", "it", "its", "just", "only", "also", "again", "next")));

    private static final Set<String> UNITS = Set.of("um", "nm", "mm", "cm", "px", "degrees", "degree", "percent", "times", "pixel", "pixels");

    private final Engine engine;

    public DefaultNlService(Engine engine) {
        this.engine = engine;
        this.confirmation = new ConfirmationPolicy(engine::schema);
    }

    private final ConfirmationPolicy confirmation;

    /** The confirmation policy over this engine's catalogue (see {@link ConfirmationPolicy}). */
    public ConfirmationPolicy confirmationPolicy() {
        return confirmation;
    }

    /** Whether, and how explicitly, the user must confirm {@code p} before its requests are submitted. */
    public ConfirmationPolicy.Decision confirmation(Proposal p) {
        return confirmation.assess(p);
    }

    /** One line describing the consequential-slot policy (shown by tools and tests). */
    public static String policyNote() {
        return "Consequential slots (threshold method, dark/black polarity, measurement set, length units and scaling, "
                + "stack scope, projection method, and any schema-flagged slot) must come from the user. Documented exceptions: "
                + "(1) boolean presence flags that are off when absent (Subtract Background 'light', sliding, disable) are simply absent when not "
                + "stated; (2) Analyze Particles uses the Fidelis reporting convention display+clear+summarize+include (derived, shown in the "
                + "preview) unless the user says otherwise; (3) Set Measurements decimals default to 9 (display only); (4) on a single image "
                + "Convert to Mask polarity is the global BlackBackground preference, stated explicitly in the preview.";
    }

    // =============================================================================================
    // speech vocabulary prompt
    // =============================================================================================

    /**
     * Hard budget of the prompt in characters. Whisper keeps the last 223 prompt tokens (about 3 characters per
     * token for technical words, so this stays well inside the limit) and the worker clips at 600 characters.
     */
    public static final int VOCABULARY_MAX_CHARS = 560;

    /** Labels of the J-12 demo journey, spoken most often and most easily misheard ("Otsu", "Watershed"). */
    private static final List<String> VOCABULARY_CORE = List.of("Subtract Background...", "Gaussian Blur...", "Convert to Mask",
            "Watershed", "Set Measurements...", "Measure", "Analyze Particles...", "Invert", "Threshold", "Otsu");

    private static final List<String> CHANNEL_NAMES = List.of("DAPI", "GFP", "FITC", "TRITC", "Cy5");

    /**
     * The vocabulary bias for the speech recogniser (to be passed to {@code SpeechService.setVocabularyPrompt}):
     * the open document titles first, then the J-12 command labels, then further labels of commands whose options
     * are catalogued (those are the ones this service can run), then common channel names. Items are added in that
     * priority order while they fit in {@link #VOCABULARY_MAX_CHARS}; nothing is cut mid-word. Titles are reduced
     * to plain word characters so that a document name cannot inject structure into the prompt.
     */
    public String vocabularyPrompt(List<String> documentTitles) {
        List<String> titles = new ArrayList<>();
        if (documentTitles != null) {
            for (String t : documentTitles) {
                String c = t == null ? "" : t.replaceAll("[^\\p{L}\\p{N} ._-]", " ").replaceAll("\\s+", " ").trim();
                if (c.length() > 40) c = c.substring(0, 40).trim();
                if (!c.isEmpty() && !titles.contains(c)) titles.add(c);
            }
        }
        List<String> labels = new ArrayList<>();
        for (String l : VOCABULARY_CORE) addLabel(labels, l);
        for (CommandSchema.Summary h : engine.catalogue()) {
            if (labels.size() >= 40) break;
            String l = h.label().replace("...", "").trim();
            // file-format entries of Save As and bit-depth conversions are not worth bias tokens
            if (h.menuPath().contains("Save As") || l.matches("(?i)\\d+-bit|RGB Color|Image|Open|PNG|Tiff|Gif|Jpeg|Text Image|Zip")) continue;
            if (engine.schema(h.commandId()).map(CommandSchema::schemaComplete).orElse(false)) addLabel(labels, h.label());
        }
        String images = segment("Images", titles, 5, 170);
        String channels = segment("Channels", CHANNEL_NAMES, 5, 60);
        String head = "ImageJ commands:";
        int room = VOCABULARY_MAX_CHARS - images.length() - channels.length() - head.length() - 1;
        StringBuilder cmds = new StringBuilder();
        for (String item : labels) {
            String piece = (cmds.length() == 0 ? " " : ", ") + item;
            if (cmds.length() + piece.length() > room) break;
            cmds.append(piece);
        }
        return (images + head + cmds + "." + channels).trim();
    }

    private static void addLabel(List<String> out, String label) {
        String l = label.replace("...", "").trim();
        if (!l.isEmpty() && !out.contains(l)) out.add(l);
    }

    /** "Heading: a, b." followed by a space (or empty), at most {@code max} items and {@code budget} characters. */
    private static String segment(String heading, List<String> items, int max, int budget) {
        StringBuilder seg = new StringBuilder(heading + ":");
        int n = 0;
        boolean any = false;
        for (String item : items) {
            if (n++ >= max) break;
            String piece = (any ? ", " : " ") + item;
            if (seg.length() + piece.length() + 2 > budget) break;
            seg.append(piece);
            any = true;
        }
        return any ? " " + seg + "." + " " : "";
    }

    // =============================================================================================
    // entry point
    // =============================================================================================

    @Override
    public Proposal interpret(String text, Origin origin, DocumentId focused) {
        Origin o = origin == Origin.NL_SPOKEN ? Origin.NL_SPOKEN : Origin.NL_TYPED;
        String norm = NlText.normalise(text);
        if (norm.isEmpty()) {
            return clarify("", List.of("What would you like to do with the image?"));
        }
        // Safety screen on the whole utterance: file/system/code vocabulary is never interpreted.
        if (DENY.matcher(norm).find()) {
            return refuse(norm, "The natural-language path only runs catalogued image-processing commands. It never "
                    + "deletes or writes files, runs macros or scripts, or executes code. Use the script editor for code.");
        }
        Optional<DocumentInfo> docOpt = focused == null ? Optional.empty() : engine.document(focused);
        boolean haveDoc = docOpt.isPresent();
        DocumentInfo doc = docOpt.orElseGet(DefaultNlService::placeholderDocument);
        DocumentId docId = haveDoc ? docOpt.get().id() : new DocumentId("none");

        List<Step> steps = new ArrayList<>();
        for (String clause : NlText.splitSteps(norm)) {
            steps.addAll(interpretClause(clause, doc, docId));
        }
        for (Step s : steps) {
            if (s.refusal != null) return refuse(norm, s.refusal);
        }
        if (!haveDoc) {
            return clarify(norm, List.of("No image is focused. Open or select the image you want to work on, then repeat the instruction."));
        }
        List<String> questions = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            for (String q : s.questions) questions.add(steps.size() > 1 ? "Step " + (i + 1) + ": " + q : q);
        }
        if (!questions.isEmpty()) return clarify(norm, questions);

        List<ActionRequest> requests = new ArrayList<>();
        List<String> menus = new ArrayList<>();
        List<String> macros = new ArrayList<>();
        List<Map<String, String>> provenance = new ArrayList<>();
        for (Step s : steps) {
            TargetScope target = new TargetScope(docId, s.channel, s.slice, s.frame, s.allPlanes, s.roiIndex);
            ActionRequest r = s.kind == ActionKind.COMMAND
                    ? ActionRequest.command(o, s.commandId, s.params, target)
                    : ActionRequest.direct(o, s.operation, s.params, target);
            requests.add(r);
            menus.add(s.menuPath);
            macros.add(s.macro + (s.note == null ? "" : "  // " + s.note));
            provenance.add(new LinkedHashMap<>(s.prov));
        }
        return new Proposal(Proposal.Status.READY, norm, List.copyOf(requests), List.copyOf(menus), List.copyOf(macros),
                List.copyOf(provenance), List.of(), null);
    }

    private static DocumentInfo placeholderDocument() {
        return new DocumentInfo(new DocumentId("none"), "none", 0, 1, 1, 1, 1, 1, "GRAY8", 8, "pixel", 1, 1, null, null, false, false);
    }

    private static Proposal refuse(String norm, String reason) {
        return new Proposal(Proposal.Status.REFUSED, norm, List.of(), List.of(), List.of(), List.of(), List.of(), reason);
    }

    private static Proposal clarify(String norm, List<String> questions) {
        return new Proposal(Proposal.Status.NEEDS_CLARIFICATION, norm, List.of(), List.of(), List.of(), List.of(),
                List.copyOf(questions), null);
    }

    // =============================================================================================
    // step under construction
    // =============================================================================================

    private static final class Step {
        ActionKind kind = ActionKind.COMMAND;
        String commandId;
        String operation;
        String menuPath;
        String macro;
        String note;
        String refusal;
        final Map<String, Object> params = new LinkedHashMap<>();
        final Map<String, String> prov = new LinkedHashMap<>();
        final List<String> questions = new ArrayList<>();
        Integer channel;
        Integer slice;
        Integer frame;
        boolean allPlanes;
        Integer roiIndex;

        void user(String k, Object v) { params.put(k, v); prov.put(k, "USER"); }

        void derived(String k, Object v) { params.put(k, v); prov.put(k, "DERIVED"); }

        void ask(String q) { if (!questions.contains(q)) questions.add(q); }

        static Step refused(String reason) {
            Step s = new Step();
            s.refusal = reason;
            return s;
        }
    }

    // =============================================================================================
    // clause interpretation: grammar (front end A), then catalogue retrieval (front end B)
    // =============================================================================================

    private List<Step> interpretClause(String c, DocumentInfo doc, DocumentId docId) {
        List<Step> r = grammar(c, doc, docId);
        if (r != null) return r;
        return List.of(retrieve(c, doc, docId));
    }

    private List<Step> grammar(String c, DocumentInfo doc, DocumentId docId) {
        if (NlText.matches(c, "^(?:undo|undo that|undo the last (?:action|step|command|operation|change)|go back)$")) {
            return List.of(direct(Operations.UNDO, "Edit>Undo", "run(\"Undo\");", Map.of()));
        }
        if (NlText.matches(c, "^redo(?: that)?$")) {
            return List.of(direct(Operations.REDO, "Edit>Redo", "// Redo has no macro form", Map.of()));
        }
        if (NlText.matches(c, "^(?:clear|remove|drop|delete) (?:the |my |this |current )?(?:selection|roi|region)$|^(?:select none|deselect(?: all)?|unselect)$")) {
            return List.of(direct(Operations.CLEAR_ROI, "Edit>Selection>Select None", "run(\"Select None\");", Map.of()));
        }
        if (NlText.matches(c, "^select all(?: of)?(?: the image)?$|^select the (?:whole|entire) image$")) {
            Step s = direct(Operations.SET_ROI, "Edit>Selection>Select All", "run(\"Select All\");", Map.of());
            float w = doc.width();
            float h = doc.height();
            s.params.put("roi", new RoiGeometry("RECTANGLE", List.of(0f, w), List.of(0f, h), new double[] {0, 0, w, h}, null, null, null));
            s.prov.put("roi", "DERIVED");
            return List.of(s);
        }
        if (NlText.matches(c, "^smooth(?: it| the image)?$")) {
            Step s = new Step();
            s.ask("Smooth with which filter: Gaussian Blur, Median, Mean, or the 3x3 Smooth command?");
            return List.of(s);
        }
        if (NlText.matches(c, "^(?:open|load|import|save|export|close|quit|exit)\\b")) {
            return List.of(Step.refused("Opening, saving and closing files is not available from natural language yet. "
                    + "Use the File menu."));
        }
        if (NlText.matches(c, "\\bgauss(?:ian)?\\b|\\bblur\\b") && !NlText.matches(c, "\\bmedian\\b|\\bmean (?:filter|blur)\\b|\\bbox blur\\b|3d")) {
            return List.of(gaussian(c, doc, docId));
        }
        if (NlText.matches(c, "\\b(?:z project|projection|project)\\b")) return List.of(zProject(c, doc, docId));
        if (NlText.matches(c, "\\bmedian\\b")) return List.of(radiusFilter(c, doc, docId, "ij1:Process>Filters>Median...", "Median"));
        if (NlText.matches(c, "\\bmean (?:filter|blur)\\b|\\bbox blur\\b|\\baverage filter\\b")) {
            return List.of(radiusFilter(c, doc, docId, "ij1:Process>Filters>Mean...", "Mean"));
        }
        if (NlText.matches(c, "\\bsubtract(?:ing)? (?:the )?background\\b|\\bremove (?:the )?background\\b|\\bbackground subtraction\\b|\\brolling ball\\b")) {
            return List.of(subtractBackground(c, doc, docId));
        }
        if (NlText.matches(c, "\\benhance contrast\\b|\\bstretch (?:the )?contrast\\b|\\bauto contrast\\b")) {
            return List.of(enhanceContrast(c, doc, docId));
        }
        if (NlText.matches(c, "\\bset (?:the )?measurements?\\b|\\bchoose measurements?\\b|\\bconfigure measurements?\\b")) {
            return List.of(setMeasurements(c, doc, docId, true));
        }
        if (NlText.matches(c, "\\bthreshold(?:ing)?\\b") && !NlText.matches(c, "\\bconvert\\b|\\bmask\\b|\\bbinary\\b")) {
            return List.of(threshold(c, doc, docId));
        }
        if (NlText.matches(c, "\\bconvert (?:it |the image |this )?to (?:a )?mask\\b|\\bbinari[sz]e\\b|\\b(?:create|make) (?:a )?mask\\b|\\bto mask\\b")) {
            return List.of(convertToMask(c, doc, docId));
        }
        if (NlText.matches(c, "\\bmake (?:it |the image |this )?(?:a )?binary(?: image)?\\b")) {
            return List.of(convertToMask(c, doc, docId, "ij1:Process>Binary>Make Binary"));
        }
        if (NlText.matches(c, "\\bwatershed\\b"))return List.of(simpleInPlace(c, doc, docId, "ij1:Process>Binary>Watershed", true));
        if (NlText.matches(c, "\\banaly[sz]e particles\\b|\\bparticle analysis\\b|\\bcount (?:the |all )?(?:particles|cells|objects|nuclei|blobs|spots)\\b")) {
            return List.of(analyzeParticles(c, doc, docId));
        }
        if (NlText.matches(c, "\\bmeasure\\b|\\bmeasurements?\\b")) return measure(c, doc, docId);
        if (NlText.matches(c, "\\binvert\\b") && !NlText.matches(c, "\\blut\\b|\\blookup\\b")) {
            return List.of(simpleInPlace(c, doc, docId, "ij1:Edit>Invert", true));
        }
        if (NlText.matches(c, "\\bduplicate\\b|\\bmake a copy\\b|\\bcopy the image\\b")) return List.of(duplicate(c, doc, docId));
        Matcher bit = Pattern.compile("\\b(8|16|32) bit\\b(?! color)").matcher(c);
        if (bit.find() && NlText.matches(c, "\\bconvert\\b|\\bchange\\b|\\bmake\\b|\\bto\\b|^\\d+ bit$")) {
            return List.of(simpleInPlace(c, doc, docId, "ij1:Image>Type>" + bit.group(1) + "-bit", false));
        }
        return null;
    }

    private Step direct(String operation, String menuPath, String macro, Map<String, Object> params) {
        Step s = new Step();
        s.kind = ActionKind.DIRECT;
        s.operation = operation;
        s.menuPath = menuPath;
        s.macro = macro;
        params.forEach(s::user);
        return s;
    }

    // ---- commands ---------------------------------------------------------------------------

    private Step simpleInPlace(String c, DocumentInfo doc, DocumentId id, String commandId, boolean scoped) {
        Step s = command(commandId);
        if (s.refusal != null) return s;
        if (scoped) scope(s, c, doc, null);
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private Step gaussian(String c, DocumentInfo doc, DocumentId id) {
        Step s = command("ij1:Process>Filters>Gaussian Blur...");
        if (s.refusal != null) return s;
        Quantity q = NlText.quantity(c, "sigma|radius|blur|gaussian|by|with", true);
        lengthSlot(s, q, "sigma", "scaled", doc, "Sigma");
        scope(s, c, doc, "stack");
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private Step radiusFilter(String c, DocumentInfo doc, DocumentId id, String commandId, String name) {
        Step s = command(commandId);
        if (s.refusal != null) return s;
        Quantity q = NlText.quantity(c, "radius|median|mean|filter|by|with", true);
        lengthSlot(s, q, "radius", null, doc, "Radius");
        scope(s, c, doc, "stack");
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private Step subtractBackground(String c, DocumentInfo doc, DocumentId id) {
        Step s = command("ij1:Process>Subtract Background...");
        if (s.refusal != null) return s;
        Quantity q = NlText.quantity(c, "rolling ball radius|rolling ball|radius|rolling|ball|background|by|with", true);
        lengthSlot(s, q, "rolling", null, doc, "Rolling-ball radius");
        if (NlText.matches(c, "\\b(?:light|bright|white) background\\b|\\bbackground (?:is )?(?:light|bright|white)\\b")) s.user("light", true);
        else if (NlText.matches(c, "\\b(?:dark|black) background\\b|\\bbackground (?:is )?(?:dark|black)\\b")) s.user("light", false);
        else {
            // "light" is a boolean presence flag (absent = off): when polarity is not stated the flag is simply absent
            // (a non-light background, the usual fluorescence case). Rule documented in policyNote(); shown in the preview.
            s.derived("light", false);
            s.note = "'light background' not requested, so the option is absent";
        }
        flag(s, c, "sliding", "\\bsliding paraboloid\\b|\\bparaboloid\\b");
        flag(s, c, "create", "\\bcreate background\\b|\\bshow background\\b");
        flag(s, c, "disable", "\\bdisable smoothing\\b|\\bno smoothing\\b|\\bwithout smoothing\\b");
        flag(s, c, "separate", "\\bseparate colou?rs\\b");
        scope(s, c, doc, "stack");
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private Step enhanceContrast(String c, DocumentInfo doc, DocumentId id) {
        Step s = command("ij1:Process>Enhance Contrast...");
        if (s.refusal != null) return s;
        Quantity q = NlText.quantity(c, "saturated(?: pixels)?|saturation", false);
        Matcher pct = Pattern.compile("(\\d+(?:\\.\\d+)?) (?:percent )?saturated").matcher(c);
        if (q == null && pct.find()) q = new Quantity(Double.parseDouble(pct.group(1)), null);
        if (q != null) {
            if (q.value() < 0 || q.value() > 100) return Step.refused("Saturated pixels must be between 0 and 100 percent (got " + fmt(q.value()) + ").");
            s.user("saturated", q.value());
        } else if (NlText.matches(c, "\\bdefault(?:s)?\\b|\\bstandard\\b")) {
            s.user("saturated", 0.35);
            s.note = "default of 0.35% saturated pixels requested by the user";
        } else {
            s.ask("What percentage of saturated pixels should Enhance Contrast use? (Fiji's own default is 0.35.)");
        }
        flag(s, c, "normalize", "\\bnormali[sz]e\\b");
        flag(s, c, "equalize", "\\bequali[sz]e\\b|\\bhistogram equali[sz]ation\\b");
        flag(s, c, "use", "\\bstack histogram\\b");
        scope(s, c, doc, "process_all");
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private Step threshold(String c, DocumentInfo doc, DocumentId id) {
        // numeric thresholds: "threshold between 50 and 200", "threshold above 100", "threshold below 80"
        Matcher range = Pattern.compile("(?:between |from )?(\\d+(?:\\.\\d+)?) (?:to|and) (\\d+(?:\\.\\d+)?)").matcher(c);
        Matcher above = Pattern.compile("(?:above|over|greater than|more than|at least|higher than) (\\d+(?:\\.\\d+)?)").matcher(c);
        Matcher below = Pattern.compile("(?:below|under|less than|at most|lower than) (\\d+(?:\\.\\d+)?)").matcher(c);
        Matcher at = Pattern.compile("(?:at|of|to|value|level) (\\d+(?:\\.\\d+)?)\\b").matcher(c);
        double max = doc.bitDepth() == 16 ? 65535 : 255;
        Double lo = null;
        Double hi = null;
        if (range.find()) {
            lo = Double.parseDouble(range.group(1));
            hi = Double.parseDouble(range.group(2));
        } else if (above.find()) {
            lo = Double.parseDouble(above.group(1));
            hi = max;
        } else if (below.find()) {
            lo = 0.0;
            hi = Double.parseDouble(below.group(1));
        } else if (at.find() && findMethod(c) == null) {
            Step s = new Step();
            s.ask("Threshold at " + at.group(1) + " selects which pixels: " + at.group(1) + " to " + fmt(max) + " (above), or 0 to "
                    + at.group(1) + " (below)?");
            return s;
        }
        if (lo != null) {
            if (doc.bitDepth() == 24) return rgbThreshold(doc);
            if (lo > hi) return Step.refused("The lower threshold (" + fmt(lo) + ") must not exceed the upper threshold (" + fmt(hi) + ").");
            if (doc.bitDepth() != 32 && (hi > max || lo < 0)) {
                return Step.refused("Threshold levels for " + doc.bitDepth() + "-bit images must lie between 0 and " + fmt(max) + ".");
            }
            Step s = direct(Operations.SET_THRESHOLD, "Image>Adjust>Threshold...",
                    "setThreshold(" + fmt(lo) + ", " + fmt(hi) + ");", Map.of());
            s.user("lower", lo);
            s.user("upper", hi);
            return s;
        }
        Step s = new Step();
        s.kind = ActionKind.DIRECT;
        s.operation = Operations.SET_AUTO_THRESHOLD;
        s.menuPath = "Image>Adjust>Threshold...";
        if (doc.bitDepth() == 24) return rgbThreshold(doc);
        String method = findMethod(c);
        if (method == null) {
            s.ask("Which threshold method should be used? Options: " + String.join(", ", THRESHOLD_METHODS) + ".");
        } else {
            s.user("method", method);
        }
        Boolean dark = darkBackground(c);
        if (dark == null) {
            s.ask("Are the objects bright on a dark background, or dark on a light background? (This sets the 'dark' option of the auto-threshold.)");
        } else {
            s.user("dark", dark);
        }
        if (method != null && dark != null) {
            s.macro = "setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");";
        }
        return s;
    }

    private Step rgbThreshold(DocumentInfo doc) {
        Step s = new Step();
        s.ask("Thresholding needs a grayscale image, but '" + doc.title() + "' is RGB. Convert it to 8-bit first (Image > Type > 8-bit), then repeat?");
        return s;
    }

    private Step convertToMask(String c, DocumentInfo doc, DocumentId id) {
        return convertToMask(c, doc, id, "ij1:Process>Binary>Convert to Mask");
    }

    private Step convertToMask(String c, DocumentInfo doc, DocumentId id, String commandId) {
        Step s = command(commandId);
        if (s.refusal != null) return s;
        if (NlText.matches(c, "\\bblack background\\b|\\bobjects white\\b|\\bwhite objects\\b")) s.user("black", true);
        else if (NlText.matches(c, "\\bwhite background\\b|\\bobjects black\\b|\\bblack objects\\b")) s.user("black", false);
        else s.ask("Should the mask have a black background (objects white) or a white background (objects black)?");
        if (doc.isStack()) {
            String method = findMethod(c);
            if (method == null) s.ask("Which threshold method should the stack conversion use? Options: " + String.join(", ", THRESHOLD_METHODS) + ".");
            else s.user("method", method);
            Boolean dark = darkBackground(c);
            if (dark == null) s.ask("Is the background dark or light for the stack conversion?");
            else s.user("background", dark ? "Dark" : "Light");
            flag(s, c, "calculate", "\\bcalculate threshold for each\\b|\\beach image\\b|\\bper slice\\b");
            scope(s, c, doc, "only", true);
        } else {
            s.derived("method", "Default");
            s.derived("background", "Default");
        }
        targetRefs(s, c, doc, id);
        complete(s, doc);
        if (!doc.isStack() && s.questions.isEmpty() && s.refusal == null && s.macro != null) {
            // On a single image method/background/calculate/only/black are not macro options: polarity comes from
            // the global BlackBackground preference (AC5), so the preview states that change explicitly.
            String label = engine.schema(commandId).map(CommandSchema::label).orElse("Convert to Mask");
            boolean black = Boolean.TRUE.equals(s.params.get("black"));
            s.macro = "setOption(" + (char) 34 + "BlackBackground" + (char) 34 + ", " + black + ");  // global preference (AC5), set explicitly" + (char) 10
                    + "run(" + (char) 34 + label + (char) 34 + ");";
        }
        return s;
    }

    private Step analyzeParticles(String c, DocumentInfo doc, DocumentId id) {
        Step s = command("ij1:Analyze>Analyze Particles...");
        if (s.refusal != null) return s;
        s.derived("size", "0-Infinity");
        s.derived("circularity", "0.00-1.00");
        particleSize(s, c, doc);
        // show= choice
        Optional<CommandSchema> schema = engine.schema(s.commandId);
        String show = null;
        for (String choice : new String[] {"Overlay Masks", "Bare Outlines", "Count Masks", "Outlines", "Ellipses", "Masks", "Overlay"}) {
            if (c.contains(choice.toLowerCase(Locale.ROOT))) { show = choice; break; }
        }
        if (show == null && NlText.matches(c, "\\bshow nothing\\b")) show = "Nothing";
        if (show != null) s.user("show", show);
        flag(s, c, "display", "\\bdisplay(?: the)?(?: results)?\\b|\\bshow results\\b|\\bresults table\\b");
        flag(s, c, "exclude", "\\bexclude(?: on)?(?: the)? edges?\\b|\\bexclude edge\\b");
        flag(s, c, "include", "\\binclude holes\\b");
        flag(s, c, "clear", "\\bclear results\\b");
        flag(s, c, "summarize", "\\bsummari[sz]e\\b|\\bsummary\\b");
        if (NlText.matches(c, "\\bcount\\b") && !s.params.containsKey("summarize")) s.derived("summarize", true);
        flag(s, c, "add", "\\badd(?:ing)? (?:them |it )?to (?:the )?(?:roi )?manager\\b");
        flag(s, c, "overlay", "\\badd to (?:the )?overlay\\b");
        flag(s, c, "record", "\\brecord starts\\b");
        flag(s, c, "composite", "\\bcomposite roi\\b");
        // Fidelis reporting convention for Analyze Particles (see policyNote): results shown, summary produced, the
        // Results table cleared of earlier runs, interior holes included; each is visible in the preview.
        for (String k : new String[] {"display", "clear", "summarize"}) {
            if (!s.params.containsKey(k)) {
                s.params.put(k, true);
                s.prov.put(k, "DEFAULT");
            }
        }
        if (!s.params.containsKey("include")) s.derived("include", true);
        flag(s, c, "in_situ", "\\bin situ\\b");
        scope(s, c, doc, "stack");
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private void particleSize(Step s, String c, DocumentInfo doc) {
        String n = "(\\d+(?:\\.\\d+)?)(?: (um2|um|nm|mm|cm|px)\\b)?";
        Double min = null;
        Double max = null;
        String unit = null;
        Matcher m = Pattern.compile("(?:larger than|bigger than|greater than|above|over|more than|at least|minimum size|min size|minimum|min)(?: size)?(?: of)? " + n).matcher(c);
        if (m.find()) {
            min = Double.parseDouble(m.group(1));
            unit = m.group(2);
        }
        m = Pattern.compile("(?:smaller than|less than|below|under|at most|maximum size|max size|maximum|max)(?: size)?(?: of)? " + n).matcher(c);
        if (m.find()) {
            max = Double.parseDouble(m.group(1));
            if (m.group(2) != null) unit = m.group(2);
        }
        if (min == null && max == null) {
            m = Pattern.compile("(?:between |from |size )?" + n + " (?:to|and) " + n).matcher(c);
            if (m.find()) {
                min = Double.parseDouble(m.group(1));
                max = Double.parseDouble(m.group(3));
                unit = m.group(4) != null ? m.group(4) : m.group(2);
            }
        }
        if (min == null && max == null) return;
        if (min != null && max != null && min > max) {
            s.refusal = "The minimum particle size (" + fmt(min) + ") must not exceed the maximum (" + fmt(max) + ").";
            return;
        }
        boolean calibrated = isCalibrated(doc);
        double factor = 1;
        if (unit == null) {
            if (calibrated) {
                s.ask("Is the particle size in pixels or in square " + unitLabel(doc.unit()) + "? (" + doc.title() + " is calibrated.)");
                return;
            }
            s.derived("pixel", false);
        } else if (unit.equals("px")) {
            s.derived("pixel", calibrated);
        } else if (unit.equals("um2")) {
            if (!calibrated) {
                s.ask("'" + doc.title() + "' has no spatial calibration, so a size in square micrometres cannot be converted. Give the size in pixels?");
                return;
            }
            double per = NlText.metresPer(doc.unit());
            if (per < 0) {
                s.ask("The image unit '" + doc.unit() + "' is not a length unit I can convert from micrometres. Give the size in pixels?");
                return;
            }
            factor = Math.pow(1e-6 / per, 2);
            s.derived("pixel", false);
            if (Math.abs(factor - 1) > 1e-9) s.note = "sizes converted from um^2 to " + doc.unit() + "^2 (x" + fmt(factor) + ")";
        } else {
            s.ask("Particle size is an area. Give it in pixels or in square micrometres (for example 'larger than 50 square microns').");
            return;
        }
        String lo = min == null ? "0" : fmt(min * factor);
        String hi = max == null ? "Infinity" : fmt(max * factor);
        s.user("size", lo + "-" + hi);
    }

    private List<Step> measure(String c, DocumentInfo doc, DocumentId id) {
        List<String> named = measurementNames(c);
        Step m = command("ij1:Analyze>Measure");
        if (m.refusal != null) return List.of(m);
        targetRefs(m, c, doc, id);
        complete(m, doc);
        if (named.isEmpty()) return List.of(m);
        Step sm = setMeasurements(c, doc, id, false);
        return List.of(sm, m);
    }

    private Step setMeasurements(String c, DocumentInfo doc, DocumentId id, boolean explicit) {
        Step s = command("ij1:Analyze>Set Measurements...");
        if (s.refusal != null) return s;
        List<String> named = measurementNames(c);
        if (named.isEmpty()) {
            s.ask("Which measurements should be recorded? For example: area, mean, min and max, integrated density, perimeter, shape descriptors, centroid.");
            return s;
        }
        for (String key : named) s.user(key, true);
        flag(s, c, "limit", "\\blimit to threshold\\b");
        Matcher d = Pattern.compile("(\\d+) decimal(?: places)?|decimals? (?:places )?(?:of |to )?(\\d+)").matcher(c);
        if (d.find()) {
            int places = Integer.parseInt(d.group(1) != null ? d.group(1) : d.group(2));
            if (places < 0 || places > 9) return Step.refused("Decimal places must be between 0 and 9.");
            s.user("decimal", (double) places);
        }
        if (!s.params.containsKey("decimal")) {
            s.params.put("decimal", 9.0); // Fidelis reports full precision; display rounding only (non-consequential)
            s.prov.put("decimal", "DEFAULT");
        }
        Optional<CommandSchema> schema = engine.schema(s.commandId);
        if (schema.isPresent()) {
            for (ParameterSchema p : schema.get().parameters()) {
                if (!s.params.containsKey(p.key()) && p.type() == ParameterType.BOOLEAN && p.consequential()) {
                    s.derived(p.key(), false); // measurements not named are not recorded
                }
                if (p.key().equals("redirect") && !s.params.containsKey("redirect")) {
                    s.derived("redirect", "None"); // measure on the image itself, not on another open image
                }
            }
            s.note = "records exactly: " + String.join(", ", named);
        }
        return complete(s, doc);
    }

    private static final String[][] MEASURES = {
        {"area fraction", "area_fraction"}, {"integrated density", "integrated"}, {"integrated intensity", "integrated"},
        {"standard deviation", "standard"}, {"std dev", "standard"}, {"stddev", "standard"}, {"center of mass", "center"},
        {"centre of mass", "center"}, {"bounding rectangle", "bounding"}, {"bounding box", "bounding"},
        {"fit ellipse", "fit"}, {"shape descriptors", "shape"}, {"feret", "feret's"}, {"min and max", "min"},
        {"minimum and maximum", "min"}, {"modal", "modal"}, {"median", "median"}, {"skewness", "skewness"},
        {"kurtosis", "kurtosis"}, {"perimeter", "perimeter"}, {"centroid", "centroid"}, {"area", "area"},
        {"mean gray value", "mean"}, {"mean grey value", "mean"}, {"mean intensity", "mean"}, {"mean", "mean"},
        {"minimum", "min"}, {"maximum", "min"}, {"min", "min"}, {"max", "min"}, {"stack position", "stack"},
        {"shape", "shape"}, {"ellipse", "fit"},
    };

    private static List<String> measurementNames(String c) {
        Map<Integer, String> byPosition = new java.util.TreeMap<>();
        String rest = c;
        for (String[] m : MEASURES) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(m[0]) + "\\b");
            Matcher mt = p.matcher(rest);
            if (mt.find()) {
                byPosition.put(mt.start(), m[1]);
                rest = mt.replaceAll(m[0].replaceAll(".", "_")); // blank it out, keeping positions
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(byPosition.values()));
    }

    private Step duplicate(String c, DocumentInfo doc, DocumentId id) {
        Step s = command("ij1:Image>Duplicate...");
        if (s.refusal != null) return s;
        String base = doc.title().replaceFirst("\\.[A-Za-z0-9]{2,4}$", "");
        Matcher t = Pattern.compile("(?:called|named|as|title) ([a-z0-9_ ]+)$").matcher(c);
        if (t.find()) s.user("title", t.group(1).trim());
        else s.derived("title", base + "-1");
        s.derived("ignore", false);
        s.derived("range", "1-" + Math.max(1, doc.nSlices() * doc.nFrames()));
        s.derived("channels", "1-" + doc.nChannels());
        s.derived("slices", "1-" + doc.nSlices());
        s.derived("frames", "1-" + doc.nFrames());
        scope(s, c, doc, "duplicate");
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    private Step zProject(String c, DocumentInfo doc, DocumentId id) {
        if (!doc.id().value().equals("none") && doc.nSlices() < 2 && doc.nFrames() < 2) {
            return Step.refused("'" + doc.title() + "' has a single slice; Z Project needs a stack.");
        }
        Step s = command("ij1:Image>Stacks>Z Project...");
        if (s.refusal != null) return s;
        String proj = null;
        if (NlText.matches(c, "\\bmax(?:imum)?\\b")) proj = "Max Intensity";
        else if (NlText.matches(c, "\\bmin(?:imum)?\\b")) proj = "Min Intensity";
        else if (NlText.matches(c, "\\bsum\\b")) proj = "Sum Slices";
        else if (NlText.matches(c, "\\bstandard deviation\\b|\\bstd\\b")) proj = "Standard Deviation";
        else if (NlText.matches(c, "\\bmedian\\b")) proj = "Median";
        else if (NlText.matches(c, "\\baverage\\b|\\bmean\\b")) proj = "Average Intensity";
        if (proj == null) s.ask("Which projection method: Maximum intensity, Average intensity, Minimum intensity, Sum of slices, Standard deviation or Median?");
        else s.user("projection", proj);
        int planes = doc.nSlices() > 1 ? doc.nSlices() : doc.nFrames();
        s.derived("start", 1.0);
        s.derived("stop", (double) planes);
        s.derived("all", false);
        targetRefs(s, c, doc, id);
        return complete(s, doc);
    }

    // =============================================================================================
    // slot helpers
    // =============================================================================================

    private Step command(String commandId) {
        Step s = new Step();
        s.commandId = commandId;
        Optional<CommandSchema> schema = engine.schema(commandId);
        if (schema.isEmpty()) {
            s.refusal = "The command " + commandId + " is not in the catalogue of this Fiji runtime.";
        }
        return s;
    }

    private static boolean isCalibrated(DocumentInfo doc) {
        String u = doc.unit();
        if (u == null) return false;
        String l = u.toLowerCase(Locale.ROOT).trim();
        return !(l.isEmpty() || l.equals("pixel") || l.equals("pixels") || l.equals("px")) && doc.pixelWidth() > 0;
    }

    private static String unitLabel(String unit) {
        String l = unit == null ? "" : unit.toLowerCase(Locale.ROOT).replace('μ', 'µ');
        return switch (l) {
            case "um", "µm", "micron", "microns", "micrometer", "micrometre" -> "micrometres";
            case "nm" -> "nanometres";
            case "mm" -> "millimetres";
            case "cm" -> "centimetres";
            default -> unit;
        };
    }

    private static String shortUnit(String token) {
        return "um".equals(token) ? "µm" : token;
    }

    /**
     * Resolve a length value (pixels, or calibrated units) into the command's slot. Commands with a
     * {@code scaled} key keep the value in calibrated units and set {@code scaled}; others get pixels.
     */
    private void lengthSlot(Step s, Quantity q, String key, String scaledKey, DocumentInfo doc, String what) {
        boolean calibrated = isCalibrated(doc);
        if (q == null) {
            s.ask(calibrated
                    ? "What " + what.toLowerCase(Locale.ROOT) + " should be used, in pixels or " + unitLabel(doc.unit()) + "? ("
                            + doc.title() + " is calibrated at " + fmt(doc.pixelWidth()) + " " + doc.unit() + " per pixel.)"
                    : "What " + what.toLowerCase(Locale.ROOT) + " should be used (in pixels)?");
            return;
        }
        if (q.value() <= 0) {
            s.refusal = what + " must be greater than 0 (got " + fmt(q.value()) + ").";
            return;
        }
        String u = q.unit();
        if (u != null && u.equals("um2")) {
            s.refusal = what + " is a length, not an area.";
            return;
        }
        if (u == null) {
            if (calibrated) {
                s.ask(what + " in pixels or " + unitLabel(doc.unit()) + "? (" + doc.title() + " is calibrated at "
                        + fmt(doc.pixelWidth()) + " " + doc.unit() + " per pixel, so the same number means different things.)");
                return;
            }
            s.user(key, q.value());
            if (scaledKey != null) s.derived(scaledKey, false);
            return;
        }
        if (u.equals("px")) {
            s.user(key, q.value());
            if (scaledKey != null) s.derived(scaledKey, false);
            return;
        }
        // physical unit
        if (!calibrated) {
            s.ask("'" + doc.title() + "' has no spatial calibration, so " + fmt(q.value()) + " " + shortUnit(u)
                    + " cannot be converted. Give the " + what.toLowerCase(Locale.ROOT) + " in pixels?");
            return;
        }
        double perDoc = NlText.metresPer(doc.unit());
        double perSpoken = NlText.metresPer(u);
        if (perDoc < 0) {
            s.ask("The image unit '" + doc.unit() + "' is not a length unit I can convert " + shortUnit(u) + " into. Give the "
                    + what.toLowerCase(Locale.ROOT) + " in pixels?");
            return;
        }
        double inDoc = Math.round(q.value() * perSpoken / perDoc * 1e9) / 1e9;
        if (scaledKey != null) {
            if (Math.abs(inDoc - q.value()) < 1e-12) s.user(key, q.value());
            else s.derived(key, inDoc);
            s.derived(scaledKey, true);
            if (Math.abs(inDoc - q.value()) > 1e-12) {
                s.note = fmt(q.value()) + " " + shortUnit(u) + " = " + fmt(inDoc) + " " + doc.unit();
            }
            return;
        }
        if (Math.abs(doc.pixelWidth() - doc.pixelHeight()) > 1e-6 * Math.max(doc.pixelWidth(), doc.pixelHeight())) {
            s.ask("The pixels of '" + doc.title() + "' are not square (" + fmt(doc.pixelWidth()) + " x " + fmt(doc.pixelHeight())
                    + " " + doc.unit() + "), so " + what.toLowerCase(Locale.ROOT) + " cannot be converted to one pixel value. Give it in pixels?");
            return;
        }
        double px = Math.round(inDoc / doc.pixelWidth() * 10000.0) / 10000.0;
        s.derived(key, px);
        s.note = fmt(q.value()) + " " + shortUnit(u) + " = " + fmt(px) + " px at " + fmt(doc.pixelWidth()) + " " + doc.unit() + "/px";
    }

    private static void flag(Step s, String c, String key, String regex) {
        if (NlText.matches(c, regex)) s.user(key, true);
    }

    private static int pickPlanes(DocumentInfo doc) {
        return doc.nSlices() > 1 ? doc.nSlices() : doc.nFrames() > 1 ? doc.nFrames() : doc.nChannels();
    }

    private void scope(Step s, String c, DocumentInfo doc, String key) {
        scope(s, c, doc, key, false);
    }

    /** Stack scope: "all slices"/"whole stack" set the key; a single-slice image never gets it. */
    private void scope(Step s, String c, DocumentInfo doc, String key, boolean inverted) {
        boolean all = NlText.matches(c, "\\b(?:all|every|each|whole|entire|full)(?: the)?(?: of the)? (?:slices|stack|images|frames|planes|slice)\\b|\\bthe stack\\b|\\bstack\\b(?! histogram)");
        boolean current = NlText.matches(c, "\\b(?:current|this|single|only|just)(?: the)? (?:slice|plane|frame)\\b|\\bcurrent image only\\b|\\bonly (?:this|the current) (?:slice|image)\\b");
        Matcher sl = Pattern.compile("\\bslice (\\d+)\\b").matcher(c);
        if (!doc.isStack()) {
            if (key != null) s.derived(key, false);
            return;
        }
        if (sl.find()) {
            int n = Integer.parseInt(sl.group(1));
            if (n < 1 || n > doc.nSlices()) {
                s.ask("'" + doc.title() + "' has " + doc.nSlices() + " slices; slice " + n + " does not exist. Which slice?");
                return;
            }
            s.slice = n;
            if (key != null) s.user(key, inverted);
            return;
        }
        if (NlText.matches(c, "\\bchannel \\d+\\b") && !all) {
            if (key != null) s.user(key, inverted); // an explicit channel means that channel only
            return;
        }
        if (all && !current) {
            s.allPlanes = true;
            if (key != null) s.user(key, !inverted);
        } else if (current && !all) {
            if (key != null) s.user(key, inverted);
        } else {
            int n = pickPlanes(doc);
            String unit = doc.nSlices() > 1 ? "slices" : doc.nFrames() > 1 ? "frames" : "channels";
            s.ask("Apply to the current slice only, or all " + n + " " + unit + "?");
        }
    }

    /** "channel 2", "frame 3", "the selection": explicit target parts, validated against the document. */
    private void targetRefs(Step s, String c, DocumentInfo doc, DocumentId id) {
        Matcher ch = Pattern.compile("\\bchannel (\\d+)\\b").matcher(c);
        if (ch.find()) {
            int n = Integer.parseInt(ch.group(1));
            if (n < 1 || n > doc.nChannels()) s.ask("'" + doc.title() + "' has " + doc.nChannels() + " channel(s); channel " + n + " does not exist. Which channel?");
            else s.channel = n;
        }
        Matcher fr = Pattern.compile("\\bframe (\\d+)\\b").matcher(c);
        if (fr.find()) {
            int n = Integer.parseInt(fr.group(1));
            if (n < 1 || n > doc.nFrames()) s.ask("'" + doc.title() + "' has " + doc.nFrames() + " frame(s); frame " + n + " does not exist. Which frame?");
            else s.frame = n;
        }
        if (NlText.matches(c, "\\b(?:the |this |current |active )?(?:selection|roi|region)\\b") && !id.value().equals("none")) {
            if (engine.activeRoi(id).isPresent()) s.roiIndex = -1;
            else s.ask("There is no active selection on '" + doc.title() + "'. Draw or select a region first, or say 'the whole image'.");
        }
    }

    private static Boolean darkBackground(String c) {
        if (NlText.matches(c, "\\b(?:dark|black) background\\b|\\bbackground (?:is )?(?:dark|black)\\b|\\b(?:bright|light|white) (?:objects|cells|spots|nuclei|particles|blobs|features|structures)\\b|\\bobjects (?:are |is )?(?:bright|light|white)\\b")) return Boolean.TRUE;
        if (NlText.matches(c, "\\b(?:light|bright|white) background\\b|\\bbackground (?:is )?(?:light|bright|white)\\b|\\b(?:dark|black) (?:objects|cells|spots|nuclei|particles|blobs|features|structures)\\b|\\bobjects (?:are |is )?(?:dark|black)\\b")) return Boolean.FALSE;
        return null;
    }

    static String findMethod(String c) {
        for (Map.Entry<String, String> e : METHOD_SPOKEN.entrySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(c).find()) {
                // "default" and "mean" are also ordinary words; require threshold context for them
                if ((e.getValue().equals("Default") || e.getValue().equals("Mean") || e.getValue().equals("Minimum"))
                        && !NlText.matches(c, "threshold|method|mask|binary|binari")) continue;
                return e.getValue();
            }
        }
        return null;
    }

    // =============================================================================================
    // completion: fill every schema key, build the macro preview
    // =============================================================================================

    private Step complete(Step s, DocumentInfo doc) {
        if (s.refusal != null || s.commandId == null) return s;
        Optional<CommandSchema> opt = engine.schema(s.commandId);
        if (opt.isEmpty()) {
            s.refusal = "The command " + s.commandId + " is not in the catalogue.";
            return s;
        }
        CommandSchema schema = opt.get();
        Map<String, Object> ordered = new LinkedHashMap<>();
        Map<String, String> prov = new LinkedHashMap<>();
        boolean handlerAsked = !s.questions.isEmpty();
        for (ParameterSchema p : schema.parameters()) {
            String k = p.key();
            if (s.params.containsKey(k)) {
                Object v = coerce(p, s.params.get(k));
                String rangeProblem = rangeProblem(p, v);
                if (rangeProblem != null) {
                    s.refusal = rangeProblem;
                    return s;
                }
                ordered.put(k, v);
                prov.put(k, s.prov.getOrDefault(k, "USER"));
                continue;
            }
            Object dv = defaultFor(p);
            boolean mustAsk = dv == null || (p.consequential() && p.type() != ParameterType.BOOLEAN)
                    || (p.type() == ParameterType.BOOLEAN && p.consequential() && !isOffDefault(p));
            if (mustAsk) {
                if (!handlerAsked) s.ask(missingQuestion(schema, p));
                continue;
            }
            ordered.put(k, dv);
            prov.put(k, p.consequential() ? "DERIVED" : "DEFAULT");
        }
        // keys the handler set that the schema lacks: derived ones are dropped (the schema decides which
        // options exist); a user-stated one that cannot be expressed is refused, never silently lost
        for (String k : s.params.keySet()) {
            if (schema.parameter(k).isEmpty() && "USER".equals(s.prov.get(k))) {
                s.refusal = "The catalogue schema of '" + schema.label() + "' has no option '" + k + "', so that request cannot be expressed.";
                return s;
            }
        }
        s.params.clear();
        s.params.putAll(ordered);
        s.prov.clear();
        s.prov.putAll(prov);
        s.menuPath = menuPathOf(schema);
        if (s.questions.isEmpty()) s.macro = macroLine(schema, ordered);
        return s;
    }

    private static String menuPathOf(CommandSchema schema) {
        return schema.menuPath() == null || schema.menuPath().isBlank() ? schema.label() : schema.menuPath() + ">" + schema.label();
    }

    private static Object coerce(ParameterSchema p, Object v) {
        if (v == null) return null;
        switch (p.type()) {
            case NUMBER:
                return v instanceof Number n ? n.doubleValue() : Double.valueOf(v.toString());
            case INTEGER:
                return v instanceof Number n ? (Object) (int) Math.round(n.doubleValue()) : Integer.valueOf(v.toString());
            case BOOLEAN:
                return v instanceof Boolean ? v : Boolean.valueOf(v.toString());
            default:
                return v.toString();
        }
    }

    private static String rangeProblem(ParameterSchema p, Object v) {
        if (v instanceof Number n && (p.type() == ParameterType.NUMBER || p.type() == ParameterType.INTEGER)) {
            double d = n.doubleValue();
            if (p.min() != null && d < p.min()) return "'" + p.key() + "' must be at least " + fmt(p.min()) + " (got " + fmt(d) + ").";
            if (p.max() != null && d > p.max()) return "'" + p.key() + "' must be at most " + fmt(p.max()) + " (got " + fmt(d) + ").";
        }
        if (v instanceof String str && p.type() == ParameterType.CHOICE && !p.choices().isEmpty()) {
            boolean ok = p.choices().stream().anyMatch(ch -> unbracket(ch).equalsIgnoreCase(str));
            if (!ok) return "'" + str + "' is not a valid " + p.key() + ". Options: " + p.choices().stream().map(DefaultNlService::unbracket).collect(java.util.stream.Collectors.joining(", ")) + ".";
        }
        return null;
    }

    /** A boolean whose catalogued default is "off" (macro mode: key absent); empty means the macro-mode default. */
    private static boolean isOffDefault(ParameterSchema p) {
        String d = p.defaultValue() == null ? "" : p.defaultValue().trim().toLowerCase(Locale.ROOT);
        return d.isEmpty() || d.startsWith("false") || d.contains("absent") || d.startsWith("n/a");
    }

    /** Default for non-consequential slots (and "off" for flags); null if none can be determined. */
    private static Object defaultFor(ParameterSchema p) {
        String d = p.defaultValue() == null ? "" : p.defaultValue().trim();
        switch (p.type()) {
            case BOOLEAN:
                return Boolean.FALSE; // macro mode: absent = false (D06 section 5)
            case NUMBER:
            case INTEGER: {
                Matcher m = Pattern.compile("^[-+]?\\d+(?:\\.\\d+)?").matcher(d);
                if (!m.find()) return null;
                double v = Double.parseDouble(m.group());
                return p.type() == ParameterType.INTEGER ? (Object) (int) Math.round(v) : (Object) v;
            }
            case CHOICE: {
                List<String> choices = p.choices() == null ? List.of() : p.choices();
                for (String ch : choices) {
                    if (d.toLowerCase(Locale.ROOT).replace("[", "").startsWith(unbracket(ch).toLowerCase(Locale.ROOT))) return unbracket(ch);
                }
                return choices.isEmpty() ? null : unbracket(choices.get(0));
            }
            case STRING:
            case PATH: {
                if (d.isEmpty() || d.startsWith("<") || d.startsWith("UNKNOWN") || d.contains("(")) return "";
                return d;
            }
            default:
                return null;
        }
    }

    private static String unbracket(String s) {
        return s.replace("[", "").replace("]", "").trim();
    }

    private static String missingQuestion(CommandSchema schema, ParameterSchema p) {
        String what = p.key().replace('_', ' ');
        String choices = p.choices() == null || p.choices().isEmpty() ? "" : " Options: " + String.join(", ", p.choices()) + ".";
        String unit = p.unit() == null || p.unit().isBlank() || p.unit().equals("n/a") ? "" : " (" + p.unit() + ")";
        return schema.label().replace("...", "").trim() + " needs a value for '" + what + "'" + unit + "." + choices
                + (p.help() == null || p.help().isBlank() ? "" : " " + p.help());
    }

    // =============================================================================================
    // macro preview (same option-string rules as the engine: numbers plain, choices and strings in
    // brackets, booleans by presence)
    // =============================================================================================

    static String macroLine(CommandSchema schema, Map<String, Object> params) {
        String opts = options(schema, params);
        return opts.isEmpty() ? "run(\"" + schema.label() + "\");" : "run(\"" + schema.label() + "\", \"" + opts + "\");";
    }

    static String options(CommandSchema schema, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (ParameterSchema p : schema.parameters()) {
            Object v = params.get(p.key());
            if (v == null) continue;
            // range filters at their no-op macro-mode value are left out of the preview (the request still carries them)
            if (p.type() == ParameterType.STRING && ("size".equals(p.key()) && "0-Infinity".equals(v)
                    || "circularity".equals(p.key()) && "0.00-1.00".equals(v))) continue;
            String part;
            switch (p.type()) {
                case BOOLEAN:
                    part = Boolean.TRUE.equals(v) ? p.key() : null;
                    break;
                case NUMBER:
                case INTEGER:
                    part = p.key() + "=" + fmt(((Number) v).doubleValue());
                    break;
                default:
                    boolean needsBrackets = p.type() == ParameterType.CHOICE || v.toString().matches(".*[\\s\\[\\]\"].*");
                    part = v.toString().isEmpty() ? null : p.key() + "=" + (needsBrackets ? "[" + v + "]" : v);
            }
            if (part != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(part);
            }
        }
        return sb.toString();
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return Long.toString(Math.round(v));
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // =============================================================================================
    // front end B: catalogue retrieval and slot filling
    // =============================================================================================

    private Step retrieve(String clause, DocumentInfo doc, DocumentId id) {
        Set<String> tokens = new LinkedHashSet<>(NlText.words(clause));
        List<CommandSchema.Summary> hits = candidates(clause, tokens);
        CommandSchema.Summary chosen = null;
        int bestTokens = 0;
        boolean bestComplete = false;
        for (CommandSchema.Summary h : hits) {
            List<String> lt = labelTokens(h.label());
            if (lt.size() < bestTokens || !labelMentioned(lt, tokens)) continue;
            Optional<CommandSchema> sc = engine.schema(h.commandId());
            if (unexplainedWords(tokens, lt, sc.orElse(null)) > 1) continue; // the clause is about something else
            boolean complete = sc.map(CommandSchema::schemaComplete).orElse(false);
            // a longer label wins; at equal length a command whose options are catalogued beats one that is not
            if (lt.size() == bestTokens && (bestComplete || !complete)) continue;
            chosen = h;
            bestTokens = lt.size();
            bestComplete = complete;
        }
        if (chosen == null) {
            return Step.refused("I could not map that to a catalogue command." + suggestions(hits));
        }
        Optional<CommandSchema> opt = engine.schema(chosen.commandId());
        if (opt.isEmpty() || !opt.get().schemaComplete()) {
            return Step.refused("'" + chosen.label().trim() + "' (" + chosen.menuPath() + ") is in Fiji, but its options are not catalogued, "
                    + "so the natural-language path cannot run it safely. Use the menu." + suggestions(hits));
        }
        CommandSchema schema = opt.get();
        Step s = command(chosen.commandId());
        if (s.refusal != null) return s;
        for (ParameterSchema p : schema.parameters()) fillFromText(s, p, clause);
        fillLoneNumber(s, schema, tokens);
        Optional<ParameterSchema> scopeKey = schema.parameter("stack");
        if (scopeKey.isPresent()) scope(s, clause, doc, "stack");
        targetRefs(s, clause, doc, id);
        return complete(s, doc);
    }

    /**
     * Candidate commands for a clause: the engine's own ranked search, plus every catalogue entry whose
     * label words all occur in the clause. The engine search is a ranking aid; on the 953-command
     * catalogue it can return nothing for a long clause ("find maxima with prominence 10"), so
     * retrieval must not depend on it alone.
     */
    private List<CommandSchema.Summary> candidates(String clause, Set<String> tokens) {
        Map<String, CommandSchema.Summary> out = new LinkedHashMap<>();
        for (CommandSchema.Summary h : engine.search(clause, 8)) out.putIfAbsent(h.commandId(), h);
        for (CommandSchema.Summary h : engine.catalogue()) {
            if (out.containsKey(h.commandId())) continue;
            if (labelMentioned(labelTokens(h.label()), tokens)) out.put(h.commandId(), h);
        }
        return new ArrayList<>(out.values());
    }

    /** One missing numeric slot and exactly one number in the clause: unambiguous ("rotate by 90"). */
    private static void fillLoneNumber(Step s, CommandSchema schema, Set<String> tokens) {
        List<ParameterSchema> missing = new ArrayList<>();
        for (ParameterSchema p : schema.parameters()) {
            boolean numeric = p.type() == ParameterType.NUMBER || p.type() == ParameterType.INTEGER;
            if (numeric && !s.params.containsKey(p.key()) && (p.consequential() || defaultFor(p) == null)) missing.add(p);
        }
        List<String> numbers = new ArrayList<>();
        for (String t : tokens) if (t.matches("[0-9]+([.][0-9]+)?")) numbers.add(t);
        if (missing.size() == 1 && numbers.size() == 1) s.user(missing.get(0).key(), Double.parseDouble(numbers.get(0)));
    }

    private static List<String> labelTokens(String label) {
        List<String> lt = new ArrayList<>();
        for (String t : label.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!t.isEmpty()) lt.add(t);
        return lt;
    }

    /** Every label word appears in the clause, and the label says more than filler words. */
    private static boolean labelMentioned(List<String> lt, Set<String> clauseTokens) {
        if (lt.isEmpty() || STOP.containsAll(lt)) return false;
        for (String t : lt) {
            boolean found = clauseTokens.contains(t) || clauseTokens.contains(t + "s")
                    || (t.endsWith("s") && clauseTokens.contains(t.substring(0, t.length() - 1)));
            if (!found) return false;
        }
        return true;
    }

    /** Alphabetic content words of the clause that neither name the command nor any of its options. */
    private static long unexplainedWords(Set<String> clauseTokens, List<String> lt, CommandSchema schema) {
        Set<String> known = new java.util.HashSet<>(lt);
        if (schema != null) {
            for (ParameterSchema p : schema.parameters()) {
                for (String w : p.key().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) known.add(w);
                if (p.choices() != null) for (String ch : p.choices()) for (String w : unbracket(ch).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) known.add(w);
            }
        }
        return clauseTokens.stream().filter(t -> t.matches("[a-z]+") && !STOP.contains(t) && !known.contains(t)
                && !known.contains(t + "s") && !(t.endsWith("s") && known.contains(t.substring(0, t.length() - 1)))
                && !UNITS.contains(t)).count();
    }

    private static void fillFromText(Step s, ParameterSchema p, String c) {
        String key = p.key().replace('_', ' ').replace("'", "");
        switch (p.type()) {
            case NUMBER:
            case INTEGER: {
                Matcher m = Pattern.compile("\\b" + Pattern.quote(key) + "(?: of| is| at| to| =| equals| by)* (\\d+(?:\\.\\d+)?)").matcher(c);
                if (m.find()) s.user(p.key(), Double.parseDouble(m.group(1)));
                break;
            }
            case BOOLEAN:
                if (NlText.matches(c, "\\b" + Pattern.quote(key) + "\\b")) s.user(p.key(), true);
                break;
            case CHOICE: {
                if (p.choices() == null) break;
                String best = null;
                for (String ch : p.choices()) {
                    String lc = ch.toLowerCase(Locale.ROOT);
                    if (NlText.matches(c, "\\b" + Pattern.quote(lc.replace('_', ' ')) + "\\b") && (best == null || lc.length() > best.length())) best = ch;
                }
                if (best != null) s.user(p.key(), best);
                break;
            }
            default:
                break;
        }
    }

    private static String suggestions(List<CommandSchema.Summary> hits) {
        if (hits == null || hits.isEmpty()) return "";
        List<String> labels = new ArrayList<>();
        for (CommandSchema.Summary h : hits) {
            if (labels.size() >= 3) break;
            String l = h.label() + " (" + h.menuPath() + ")";
            if (!labels.contains(l)) labels.add(l);
        }
        return " Closest catalogue commands: " + String.join("; ", labels) + ".";
    }

    /** For tests and tools: the thresholds methods accepted. */
    static List<String> thresholdMethods() {
        return Arrays.asList(THRESHOLD_METHODS.toArray(new String[0]));
    }
}

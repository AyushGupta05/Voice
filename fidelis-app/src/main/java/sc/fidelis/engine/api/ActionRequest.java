package sc.fidelis.engine.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.Origin;

/**
 * The single request structure produced by every input modality (docs D06 §2, A18).
 *
 * <p>For {@link ActionKind#COMMAND}, {@code commandId} names a catalogue command (for example
 * {@code ij1:Process>Filters>Gaussian Blur...}) and {@code params} must contain <em>every</em> key of
 * its {@link CommandSchema}: booleans explicit, no reliance on remembered IJ1 values (FR-PROC-003).
 *
 * <p>For {@link ActionKind#DIRECT} and {@link ActionKind#SESSION}, {@code operation} names the
 * engine operation (see {@link Operations}) and {@code params} carries its arguments.
 *
 * <p>For {@link ActionKind#CODE}, {@code params} carries {@code "text"} (macro source) and
 * {@code "language"}; execution requires a capability confirmation token (FR-MACRO-002).
 *
 * @param confirmationToken token issued by the engine when confirmation is required, else null
 * @param replayOf          the history {@code actionId} this request replays, else null
 */
public record ActionRequest(
        String requestId,
        Origin origin,
        ActionKind kind,
        String commandId,
        String operation,
        Map<String, Object> params,
        TargetScope target,
        String confirmationToken,
        String replayOf) {

    public ActionRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(kind, "kind");
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        if (kind == ActionKind.COMMAND && (commandId == null || commandId.isBlank()))
            throw new IllegalArgumentException("COMMAND requests need a commandId");
        if ((kind == ActionKind.DIRECT || kind == ActionKind.SESSION) && (operation == null || operation.isBlank()))
            throw new IllegalArgumentException(kind + " requests need an operation");
    }

    public static ActionRequest command(Origin origin, String commandId, Map<String, Object> params, TargetScope target) {
        return new ActionRequest(UUID.randomUUID().toString(), origin, ActionKind.COMMAND, commandId, null, params, target, null, null);
    }

    public static ActionRequest direct(Origin origin, String operation, Map<String, Object> params, TargetScope target) {
        return new ActionRequest(UUID.randomUUID().toString(), origin, ActionKind.DIRECT, null, operation, params, target, null, null);
    }

    public static ActionRequest session(Origin origin, String operation, Map<String, Object> params, TargetScope target) {
        return new ActionRequest(UUID.randomUUID().toString(), origin, ActionKind.SESSION, null, operation, params, target, null, null);
    }

    public static ActionRequest code(Origin origin, String language, String text, TargetScope target, String confirmationToken) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("language", language);
        p.put("text", text);
        return new ActionRequest(UUID.randomUUID().toString(), origin, ActionKind.CODE, null, "RUN_CODE", p, target, confirmationToken, null);
    }

    public ActionRequest withConfirmation(String token) {
        return new ActionRequest(requestId, origin, kind, commandId, operation, params, target, token, replayOf);
    }

    /** Names of the DIRECT and SESSION operations the engine understands (docs D06 §2, §5). */
    public static final class Operations {
        private Operations() {}
        // SESSION
        public static final String OPEN = "OPEN";                 // params: path
        public static final String SAVE_AS = "SAVE_AS";           // params: path, format (tiff|png|jpeg…)
        public static final String CLOSE = "CLOSE";               // params: discard (boolean)
        public static final String NEW_IMAGE = "NEW_IMAGE";       // params: title, type, width, height, slices, fill
        // DIRECT
        public static final String SET_ROI = "SET_ROI";           // params: roi (RoiGeometry)
        public static final String CLEAR_ROI = "CLEAR_ROI";
        public static final String ROI_LIST_ADD = "ROI_LIST_ADD";           // adds the active selection to the document's ROI list
        public static final String ROI_LIST_SELECT = "ROI_LIST_SELECT";     // params: index
        public static final String ROI_LIST_DELETE = "ROI_LIST_DELETE";     // params: index
        public static final String ROI_LIST_RENAME = "ROI_LIST_RENAME";     // params: index, name
        public static final String SET_DISPLAY_RANGE = "SET_DISPLAY_RANGE"; // params: min, max (display only, AC1)
        public static final String SET_POSITION = "SET_POSITION";           // params: channel, slice, frame
        public static final String SET_THRESHOLD = "SET_THRESHOLD";         // params: lower, upper (non-destructive)
        public static final String SET_AUTO_THRESHOLD = "SET_AUTO_THRESHOLD"; // params: method, dark (boolean)
        public static final String RESET_THRESHOLD = "RESET_THRESHOLD";
        public static final String UNDO = "UNDO";
        public static final String REDO = "REDO";
    }
}

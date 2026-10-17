/**
 * The Fidelis engine over the real Fiji/ImageJ runtime (hybrid B). Entry point:
 * {@link sc.fidelis.engine.DefaultEngine#create}; the running engine is available through
 * {@link sc.fidelis.engine.Engines#current()}.
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li><b>Threads.</b> One worker, {@code fidelis-engine}, executes every operation. The EDT is used only to
 *       create and dispose hidden host windows. Events are delivered on the EDT in sequence order.</li>
 *   <li><b>Documents.</b> Each registered image keeps a hidden {@code ImageWindow} with a {@code HostCanvas} and a
 *       null layout for its whole life. Images IJ1 creates itself (Duplicate, Analyze Particles masks) are adopted
 *       after each action and after native commands, by re-hosting them.</li>
 *   <li><b>Reserved metadata keys</b> in {@code ActionRequest.params}, accepted for every kind and never part of
 *       the options string: {@code transcriptRef} (copied to {@code HistoryRecord.transcriptRef}) and
 *       {@code parentActionId} (copied to the record params; exported macros skip a nested record when its parent
 *       has macro lines).</li>
 *   <li><b>Completeness.</b> Every schema key must be given, except keys whose {@code visibleWhen} predicate is false
 *       for the target (for example {@code scaled} on an uncalibrated image, {@code stack} on a single image); for
 *       Convert to Mask and Make Binary every key is optional on single images.</li>
 *   <li><b>Options strings.</b> Numbers are locale independent without trailing zeros; booleans are present only when
 *       true; choices, strings and paths are always bracketed ({@code key=[value]}). A value whose brackets are
 *       unbalanced is single-quoted; one that is both unbalanced and contains a single quote, or a control character,
 *       is rejected with VALIDATION_FAILED (the limits of {@code ij.Macro.getValue}).</li>
 *   <li><b>Globals.</b> {@code black=true} for Convert to Mask or Make Binary on a single image sets
 *       {@code Prefs.blackBackground} explicitly (recorded delta, {@code setOption} line in the macro); it is restored
 *       if the action fails.</li>
 *   <li><b>History.</b> Failed and rolled-back attempts are recorded too (outcome set); the export skips them,
 *       undone steps, observed commands nested under a CODE step, and UNRECORDABLE steps (as comments).
 *       {@code globalsDelta} values are {@code old=new} text.</li>
 *   <li><b>Window policy.</b> The IJ1 Log, Results and ROI Manager windows are kept hidden; after CODE actions the
 *       documents' windows are hidden again (a macro ending in {@code setBatchMode(false)} re-shows them).</li>
 * </ul>
 */
package sc.fidelis.engine;

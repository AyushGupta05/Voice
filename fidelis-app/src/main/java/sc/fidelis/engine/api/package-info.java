/**
 * The Fidelis engine contract: the single typed interface through which every input modality
 * (menus, tools, typed text, speech, macros, replay) operates on the real Fiji/ImageJ state.
 *
 * <p>Design sources: docs D06 (command contract), D09 (API and events), D05 (state ownership),
 * revised by the architecture-gate review (hybrid B: real IJ1 canvases hosted in the workspace).
 *
 * <p>Four kinds of interaction are distinguished (see {@link sc.fidelis.engine.api.Provenance}):
 * <ol>
 *   <li>NATIVE: handled directly by Fiji (canvas tools, keys) and <em>observed</em> by the engine;</li>
 *   <li>TYPED: programmatic actions submitted as {@link sc.fidelis.engine.api.ActionRequest}s;</li>
 *   <li>LEGACY_DIALOG: operations that ran through an original Fiji dialog or window;</li>
 *   <li>UNRECORDABLE: operations that cannot be recorded or replayed faithfully.</li>
 * </ol>
 *
 * <p>Stability rule: this package is shared by all workstreams. Changes require lead review and a
 * note in docs/03-development/ACTIVE-DEVELOPMENT.md. Add, do not break.
 */
package sc.fidelis.engine.api;

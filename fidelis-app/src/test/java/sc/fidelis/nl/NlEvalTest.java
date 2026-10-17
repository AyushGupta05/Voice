package sc.fidelis.nl;

import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;

/** The labelled evaluation set against the catalogue-only {@link FakeEngine} (fast, no Fiji needed). */
class NlEvalTest extends NlEvalSupport {
    @Override protected Engine createEngine() { return new FakeEngine(); }

    @Override protected DocumentId stateId(String state) { return "none".equals(state) ? null : new DocumentId(state); }
}

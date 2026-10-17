package sc.fidelis.engine.api;

/** Small shared enumerations of the engine contract (one file keeps the contract easy to review). */
public final class Enums {
    private Enums() {}

    /** How an action was initiated (docs D06 §2). */
    public enum Origin { MENU, SEARCH, SHORTCUT, CANVAS, PANEL, NL_TYPED, NL_SPOKEN, MACRO, REPLAY, OBSERVED }

    /** What an {@link ActionRequest} asks for (docs A18 §1). */
    public enum ActionKind {
        /** A catalogue command (an ImageJ menu command, run by label with complete options). */
        COMMAND,
        /** A direct engine operation on state (ROI, display range, position, undo…). */
        DIRECT,
        /** Session operations: open, save, close. */
        SESSION,
        /** User-provided macro or script text (full host privileges; needs capability consent). */
        CODE
    }

    /** Action consequence classes (docs A19 §3). */
    public enum ConsequenceClass {
        AC0_READ_ONLY, AC1_VIEW, AC2_SELECTION, AC3_REVERSIBLE_DATA, AC4_NON_UNDOABLE_DATA,
        AC5_GLOBAL_SETTINGS, AC6_PERSISTENT_EXTERNAL, AC7_CODE
    }

    /** How faithfully a history record can be replayed (docs D07 §1, brief section 6). */
    public enum Provenance {
        /** Handled directly by Fiji (canvas tool, key) and observed; macro form may be exact or absent. */
        NATIVE,
        /** Programmatic typed command with complete parameters; exact macro form. */
        TYPED,
        /** Ran through an original Fiji dialog or plugin window; parameters captured only if visible. */
        LEGACY_DIALOG,
        /** Cannot be recorded or replayed faithfully (plugin-internal state, composite edits…). */
        UNRECORDABLE
    }

    /** Operation lifecycle states (docs D06 §6). */
    public enum OperationState {
        QUEUED, VALIDATING, AWAITING_CONFIRMATION, RUNNING, WAITING_FOR_DIALOG,
        COMPLETED, FAILED, CANCELLED, ROLLED_BACK, PARTIAL
    }

    /** Engine lifecycle (docs D03 §3 as revised: UI shows from INITIALISING onwards). */
    public enum EngineState { INITIALISING, BUILDING_CATALOGUE, READY, FAILED, QUITTING }

    /** Error codes (docs D06 §7). */
    public enum ErrorCode {
        ENGINE_NOT_READY, UNKNOWN_COMMAND, VALIDATION_FAILED, PRECONDITION_FAILED,
        CONFIRMATION_REQUIRED, CAPABILITY_CONFIRMATION_REQUIRED, DIALOG_PENDING,
        EXECUTION_FAILED, POSTCONDITION_FAILED, CANCELLED, PARTIAL_RESULT, TIMEOUT,
        ENGINE_LOST, SNAPSHOT_BUDGET_EXCEEDED, IO_ERROR, UNSUPPORTED_IN_MODE
    }

    /** Parameter value types of a {@link CommandSchema}. */
    public enum ParameterType { NUMBER, INTEGER, BOOLEAN, CHOICE, STRING, IMAGE_REF, PATH }
}

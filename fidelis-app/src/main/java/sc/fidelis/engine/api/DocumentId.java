package sc.fidelis.engine.api;

import java.util.Objects;

/** Stable session-scoped identity of an image document (docs D05 §6). Never an ImagePlus title. */
public record DocumentId(String value) {
    public DocumentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("blank document id");
    }

    @Override
    public String toString() { return value; }
}

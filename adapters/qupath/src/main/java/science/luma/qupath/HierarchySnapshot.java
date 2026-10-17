package science.luma.qupath;

import java.io.*;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Private in-memory native serialization, never accepts serialized data from a client or file. */
final class HierarchySnapshot {
    private static final int MAX_BYTES = 512 * 1024 * 1024;
    static byte[] capture(PathObjectHierarchy hierarchy) {
        var bytes = new ByteArrayOutputStream() {
            @Override public synchronized void write(int value) { check(1); super.write(value); }
            @Override public synchronized void write(byte[] value,int offset,int length) { check(length); super.write(value,offset,length); }
            private void check(int length) { if ((long)count + length > MAX_BYTES) throw new IllegalStateException("Checkpoint exceeds 512 MiB"); }
        };
        try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(hierarchy); }
        catch (IOException error) { throw new IllegalStateException("Cannot preserve this hierarchy; no mutation started.",error); }
        return bytes.toByteArray();
    }
    static PathObjectHierarchy restore(byte[] bytes) {
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            // Deserialized hierarchies have uninitialized transient fields. Copy into a live instance.
            var result = new PathObjectHierarchy();
            result.setHierarchy((PathObjectHierarchy)stream.readObject());
            return result;
        } catch (IOException | ClassNotFoundException error) { throw new IllegalStateException("Cannot restore the in-memory hierarchy checkpoint.",error); }
    }
}

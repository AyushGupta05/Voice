package sc.fidelis;

/**
 * Fidelis entry point. Runs inside the pinned Fiji runtime (launch with fidelis-app/tools/run.py).
 *
 * <p>This class must not reference any {@code ij.*} class: the ImageJ1 patcher has to run before
 * ImageJ1 loads (docs A07 CR-A07-02, probe P42). All real work happens in {@link Bootstrap}.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        net.imagej.patcher.LegacyInjector.preinit();
        Bootstrap.run(args);
    }
}

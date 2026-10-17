package sc.fidelis.ui;

import java.util.ServiceLoader;

import sc.fidelis.nl.NlService;
import sc.fidelis.speech.SpeechService;

/**
 * Late-bound access to the optional services owned by the Voice and NL workstream. The workspace
 * works without them: the command bar then says the interpreter is not available and the
 * microphone is disabled with a reason.
 */
public final class ServiceRegistry {
    private static volatile NlService nl;
    private static volatile SpeechService speech;
    private static volatile boolean nlLoaded;
    private static volatile boolean speechLoaded;

    private ServiceRegistry() {}

    public static void registerNl(NlService service) {
        nl = service;
        nlLoaded = true;
    }

    public static void registerSpeech(SpeechService service) {
        speech = service;
        speechLoaded = true;
    }

    /** The registered interpreter, else one found through {@link ServiceLoader}, else null. */
    public static NlService nl() {
        if (nl == null && !nlLoaded) {
            nlLoaded = true;
            for (NlService s : ServiceLoader.load(NlService.class)) {
                nl = s;
                break;
            }
        }
        return nl;
    }

    /** The registered speech service, else one found through {@link ServiceLoader}, else null. */
    public static SpeechService speech() {
        if (speech == null && !speechLoaded) {
            speechLoaded = true;
            for (SpeechService s : ServiceLoader.load(SpeechService.class)) {
                speech = s;
                break;
            }
        }
        return speech;
    }
}

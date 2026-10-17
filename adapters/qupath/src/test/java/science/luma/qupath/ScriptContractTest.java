package science.luma.qupath;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.lang.reflect.Proxy;
import qupath.lib.projects.Project;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.gui.scripting.languages.GroovyLanguage;

public final class ScriptContractTest {
    public static void main(String[] ignored) throws Exception {
        var image=new ImageData<BufferedImage>(new WrappedBufferedImageServer("Captured script fixture",new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB)));
        @SuppressWarnings("unchecked") var project=(Project<BufferedImage>)Proxy.newProxyInstance(Project.class.getClassLoader(),new Class<?>[]{Project.class},(proxy,method,arguments)-> {
            if (method.getName().equals("toString")) return "Synthetic project";
            throw new UnsupportedOperationException("Not needed by fixture: " + method.getName());
        });
        expectAbsentContextRejected(null,image);
        expectAbsentContextRejected(project,null);
        var parameters=LumaExtension.scriptParameters(project,image,"return [name: getCurrentImageData().getServer().getMetadata().getName(), thread: Thread.currentThread().getName()]");
        var completed = new CompletableFuture<Map<?,?>>();
        Thread.ofPlatform().name("luma-script-contract").start(() -> {
            try { completed.complete((Map<?,?>)GroovyLanguage.getInstance().execute(parameters)); }
            catch (Throwable error) { completed.completeExceptionally(error); }
        });
        var result=completed.get(20,TimeUnit.SECONDS);
        if (!"Captured script fixture".equals(result.get("name")) || !"luma-script-contract".equals(result.get("thread"))) throw new AssertionError("Script must use captured image bindings outside JavaFX: " + result);
        System.out.println("Approved script parameters preserve captured image bindings on a worker: passed");
    }
    private static void expectAbsentContextRejected(Project<BufferedImage> project,ImageData<BufferedImage> image) {
        try {LumaExtension.scriptParameters(project,image,"return 1");}
        catch(IllegalStateException expected){return;}
        throw new AssertionError("Absent bindings must never fall back to GUI defaults");
    }
}

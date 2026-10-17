package science.luma.qupath;

import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.io.PathIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.Gson;

/** Test-only socket wiring around the real adapter and actual QuPath GUI. Never installed normally. */
public final class SyntheticImportFixture {
    public static void start(String directory) {
        Platform.runLater(()->{
            try {
                var root=Path.of(directory).toRealPath();
                var nonce=Files.readString(root.resolve("nonce")).trim();
                if(!nonce.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid fixture identity");
                var token=Files.readString(root.resolve(".local/state/bridge-token")).trim();
                if(!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid fixture token");
                var gui=QuPathGUI.getInstance();
                if(gui==null||gui.getImageData()!=null||gui.getProject()!=null)throw new IllegalStateException("Disposable instance must start empty");
                var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,((x/16+y/16)%2==0)?0x27483d:0xa4ebc9);
                var data=new ImageData<BufferedImage>(new WrappedBufferedImageServer("Luma synthetic import "+nonce,image),ImageData.ImageType.BRIGHTFIELD_OTHER);
                gui.getViewer().setImageData(data);
                var a=PathObjects.createAnnotationObject(ROIs.createRectangleROI(20,30,80,60,ImagePlane.getDefaultPlane()));
                var b=PathObjects.createAnnotationObject(ROIs.createRectangleROI(180,150,100,90,ImagePlane.getDefaultPlane()));
                a.setID(UUID.fromString("11111111-1111-4111-8111-111111111111"));b.setID(UUID.fromString("22222222-2222-4222-8222-222222222222"));
                a.setName("Synthetic A");b.setName("Synthetic B");a.setPathClass(PathClass.fromString("Fixture A"));b.setPathClass(PathClass.fromString("Fixture B"));
                a.getMeasurementList().put("Synthetic score",1);b.getMeasurementList().put("Synthetic score",3);
                PathIO.exportObjectsAsGeoJSON(root.resolve("annotations.geojson"),List.of(a,b));
                var bridge=new LumaExtension();
                var appField=LumaExtension.class.getDeclaredField("qupath");appField.setAccessible(true);appField.set(bridge,gui);
                var tokenField=LumaExtension.class.getDeclaredField("token");tokenField.setAccessible(true);tokenField.set(bridge,token);
                var handler=LumaExtension.class.getDeclaredMethod("handle",com.sun.net.httpserver.HttpExchange.class);handler.setAccessible(true);
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
                server.setExecutor(Executors.newFixedThreadPool(4,r->{var t=new Thread(r,"synthetic-import-http");t.setDaemon(true);return t;}));
                server.createContext("/",exchange->{try{handler.invoke(bridge,exchange);}catch(Exception error){exchange.close();}});
                server.start();Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));
                Files.writeString(root.resolve("ready.json"),new Gson().toJson(Map.of("nonce",nonce,"port",server.getAddress().getPort(),"pid",ProcessHandle.current().pid(),"imageName","Luma synthetic import "+nonce)),StandardOpenOption.CREATE_NEW);
            } catch(Throwable error) { error.printStackTrace(); /* Parent preserves an uncertain instance for inspection. */ }
        });
    }
}

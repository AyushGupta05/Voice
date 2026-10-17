package science.luma.qupath;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class BridgeIdentityContractTest {
    public static void main(String[] ignored) throws Exception {
        var identity=BridgeIdentity.description();
        if(identity!=BridgeIdentity.description())throw new AssertionError("Loaded identity must be cached, never read from a replaced jar");
        if(!String.valueOf(identity.get("sourceSha256")).matches("[a-f0-9]{64}"))throw new AssertionError("Missing real build stamp");
        if(!identity.get("protocolVersion").equals(1)||BridgeIdentity.OPERATIONS.size()!=46)throw new AssertionError("Unexpected capability protocol");
        if(!BridgeIdentity.readDigest(null).equals("unknown"))throw new AssertionError("Manual builds without a stamp are unverified");
        var invalid=new ByteArrayInputStream("sourceSha256=untrusted\n".getBytes(StandardCharsets.UTF_8));
        if(!BridgeIdentity.readDigest(invalid).equals("unknown"))throw new AssertionError("Invalid stamp accepted");
        System.out.println("Startup-cached bridge identity, build resource and declared-operation contract: passed");
    }
}

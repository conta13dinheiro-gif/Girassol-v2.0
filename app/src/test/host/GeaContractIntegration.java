import com.enhikyy.girassol.cognition.GeaNativeBridge;

public final class GeaContractIntegration {
    public static void main(String[] args) {
        boolean expected = args.length == 1 && args[0].equals("present");
        if (GeaNativeBridge.INSTANCE.getAvailable() != expected) {
            throw new AssertionError("Native availability did not match isolated library path");
        }
        if (expected) {
            GeaNativeBridge.INSTANCE.requireAvailable();
        } else {
            try {
                GeaNativeBridge.INSTANCE.requireAvailable();
                throw new AssertionError("Missing JNI library accepted");
            } catch (UnsatisfiedLinkError expectedError) {
                // Missing native backend is an observable Java error, not process termination.
            }
        }
        System.out.println("GEA native contract: " + (expected ? "present" : "absent") + " passed");
    }
}

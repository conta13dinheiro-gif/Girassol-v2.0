import com.enhikyy.girassol.cognition.GeaNativeBridge;

public final class GeaContractIntegration {
    public static void main(String[] args) {
        boolean expected = args.length == 1 && args[0].equals("present");
        if (GeaNativeBridge.INSTANCE.getAvailable() != expected) {
            throw new AssertionError("Native availability did not match isolated library path");
        }
        if (expected) {
            GeaNativeBridge.INSTANCE.requireAvailable();
            byte[] state = new byte[0];
            for(int i=0;i<20000;i++) state=GeaNativeBridge.INSTANCE.tsetlinTrain(state,i%2,i%2,i+1);
            if(state.length!=516) throw new AssertionError("unbounded Tsetlin state");
            if(GeaNativeBridge.INSTANCE.tsetlinVote(state,1,false)<=0 || GeaNativeBridge.INSTANCE.tsetlinVote(state,0,false)>=0)
                throw new AssertionError("Tsetlin failed binary learning");
            byte[] restored=state.clone();
            if(GeaNativeBridge.INSTANCE.tsetlinVote(restored,1,false)!=GeaNativeBridge.INSTANCE.tsetlinVote(state,1,false))
                throw new AssertionError("binary restore differs");
            try { GeaNativeBridge.INSTANCE.tsetlinTrain(new byte[3],0,1,1);throw new AssertionError("corrupt blob accepted"); }
            catch(IllegalArgumentException correct) { }
            try { GeaNativeBridge.INSTANCE.tsetlinVote(state,65536,false);throw new AssertionError("invalid bits accepted"); }
            catch(IllegalArgumentException correct) { }
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

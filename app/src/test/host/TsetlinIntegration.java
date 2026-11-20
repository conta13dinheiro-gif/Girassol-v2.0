import com.enhikyy.girassol.cognition.GeaNativeBridge;
import java.util.Arrays;

public final class TsetlinIntegration {
    public static void main(String[] args) {
        GeaNativeBridge bridge = GeaNativeBridge.INSTANCE;
        bridge.requireAvailable();
        long first = bridge.nativeCreateTsetlin(8, 32, 64, 3.5f, 8, 0x47525354L);
        if (first == 0L) throw new AssertionError("Tsetlin create failed");
        byte[] positive = new byte[] {1,1,1,1,0,0,0,0};
        byte[] negative = new byte[] {0,0,0,0,1,1,1,1};
        try {
            for (int i = 0; i < 200; i++) {
                if (!bridge.nativeTsetlinTrainStep(first, positive, true)) throw new AssertionError("positive training failed");
                if (!bridge.nativeTsetlinTrainStep(first, negative, false)) throw new AssertionError("negative training failed");
            }
            int before = bridge.nativeTsetlinPredictVote(first, positive);
            byte[] snapshot = bridge.nativeTsetlinSerialize(first);
            if (snapshot == null || snapshot.length < 32) throw new AssertionError("snapshot missing");
            long restored = bridge.nativeTsetlinRestore(snapshot);
            if (restored == 0L) throw new AssertionError("restore failed");
            try {
                if (bridge.nativeTsetlinPredictVote(restored, positive) != before) {
                    throw new AssertionError("restored inference diverged");
                }
                for (int i = 0; i < 100; i++) {
                    bridge.nativeTsetlinTrainStep(first, positive, true);
                    bridge.nativeTsetlinTrainStep(restored, positive, true);
                }
                if (!Arrays.equals(bridge.nativeTsetlinSerialize(first), bridge.nativeTsetlinSerialize(restored))) {
                    throw new AssertionError("restored RNG/state is not deterministic");
                }
            } finally {
                bridge.nativeDestroyTsetlin(restored);
            }
        } finally {
            bridge.nativeDestroyTsetlin(first);
        }
        System.out.println("Tsetlin JNI create/train/predict/serialize/restore passed");
    }
}

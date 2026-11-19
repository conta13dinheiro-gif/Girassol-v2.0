import com.enhikyy.girassol.core.*;
import com.enhikyy.girassol.cognition.NativeAutonomy;
import java.util.*;
import java.lang.reflect.*;

public class AutonomyIntegration {
  public static void main(String[] args) throws Exception {
    WorldState world = WorldActor.Companion.genesis(20261119L);
    var minds = new LinkedHashMap<String,MindState>();
    for (var citizen : world.getAgents()) minds.put(citizen.getId(), MindState.Companion.initial(world,citizen));
    var before = minds.get(world.getAgents().get(0).getId()).getPsyche().getAgeYears();
    // Use real production JNI output as next input, including float packing.
    for (int i=0;i<1000;i++) {
      var result = NativeAutonomy.INSTANCE.evaluate(world,List.of(),minds);
      for (var id : minds.keySet()) {
        var old=minds.get(id);
        var psyche=result.getPsyches().get(id);
        minds.put(id,new MindState(old.getParameters(),old.getHidden(),old.getPersonality(),
          old.getExposure(),old.getLearnedTick(),old.getNovelty(),old.getBehaviors(),old.getMemory(),psyche));
      }
    }
    var after=minds.get(world.getAgents().get(0).getId()).getPsyche().getAgeYears();
    var expected=1000.0/60.0/(365.25*24*3600);
    if (Math.abs((after-before)-expected)>1e-10) throw new AssertionError("age does not accumulate");
    Method advance=NativeAutonomy.class.getDeclaredMethod("advance",long[].class,float[].class,float.class);
    advance.setAccessible(true);
    try { advance.invoke(NativeAutonomy.INSTANCE,new long[]{1},new float[1],1f); throw new AssertionError("bad stride accepted"); }
    catch (InvocationTargetException error) {
      if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
    }
    System.out.println("Production autonomy JNI: repeated age integration and invalid dimensions passed");
  }
}

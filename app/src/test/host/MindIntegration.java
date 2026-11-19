import com.enhikyy.girassol.core.*;
import com.enhikyy.girassol.cognition.NativeMind;
import java.util.*;
public class MindIntegration {
  public static void main(String[] args) {
    WorldActor writer=new WorldActor(new WorldActor().checkpoint(),4), reader=new WorldActor();
    List<ActionRecord> records=List.of();
    Map<String,MindState> first=null;
    int episodes=0, reflections=0, skills=0;
    for(int i=0;i<500;i++) {
      CognitiveFrame input=NativeMind.INSTANCE.evaluate(writer.getState(),records,Collections.nCopies(8,0.0),ThermalTier.NONE_OR_LIGHT);
      if(!input.applicable(writer.getState())) throw new AssertionError("invalid native frame at "+i);
      if(first==null) first=input.getMinds();
      Transition step=writer.step(null,input,null);
      reader.replay(Canonical.INSTANCE.event(Canonical.INSTANCE.event(step.getEvent())));
      if(!writer.checkpoint().equals(reader.checkpoint())) throw new AssertionError("divergence at "+i);
      records=step.getEvent().getActions();
    }
    boolean learned=false;
    for(var pair:writer.getState().getMinds().entrySet()) {
      var mind=pair.getValue();
      learned|=!mind.getParameters().equals(first.get(pair.getKey()).getParameters());
      episodes+=mind.getMemory().getEpisodes().size();
      reflections+=mind.getMemory().getReflections().size();
      skills+=mind.getMemory().getProcedures().size();
    }
    if(!learned || episodes==0 || reflections==0 || skills==0) throw new AssertionError("learning/memory not exercised: "+episodes+"/"+reflections+"/"+skills);
    System.out.println("500 production Kotlin/JNI/LTC/Hebbian/HNSW transitions replayed exactly; episodes="+episodes+", reflections="+reflections+", techniques="+skills);
  }
}

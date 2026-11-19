import com.enhikyy.girassol.core.*;
import com.enhikyy.girassol.cognition.*;
import java.util.*;
public class ShadowIntegration {
  public static void main(String[] args) {
    WorldActor original=new WorldActor();
    Checkpoint checkpoint=original.checkpoint();
    var base=Collections.nCopies(8,0.0);
    var one=ShadowReplay.INSTANCE.rollout(checkpoint,base,List.of(),256,()->false);
    var two=ShadowReplay.INSTANCE.rollout(checkpoint,base,List.of(),256,()->false);
    WorldActor live=new WorldActor(checkpoint,WorldActor.CURRENT_RULES_VERSION);
    List<ActionRecord> actions=List.of();
    for(int i=0;i<256;i++) {
      var input=NativeMind.INSTANCE.evaluate(live.getState(),actions,base,ThermalTier.NONE_OR_LIGHT);
      var spatial=com.enhikyy.girassol.physics.NativeSpatial.INSTANCE.advance(live.getState(),live.intentions(null,input));
      var transition=live.step(null,input,spatial);
      if(transition.getEvent().getVersion()!=WorldActor.CURRENT_RULES_VERSION) throw new AssertionError("wrong envelope version");
      actions=transition.getEvent().getActions();
    }
    if(!one.getCheckpoint().equals(live.checkpoint())) throw new AssertionError("shadow differs from live rules");
    if(!one.equals(two)) throw new AssertionError("non deterministic shadow");
    if(!checkpoint.equals(original.checkpoint())) throw new AssertionError("source modified");
    var result=ShadowReplay.INSTANCE.search(checkpoint,List.of(),()->false,(done,total)->{ System.out.println("Evaluated "+done+"/"+total);return kotlin.Unit.INSTANCE; });
    if(result.getEvaluated()<21) throw new AssertionError("missing candidates");
    if(result.getRevision()!=null && !result.getRevision().valid(checkpoint.getState().getSeed(),0)) throw new AssertionError("unsafe promotion");
    if(!checkpoint.equals(original.checkpoint())) throw new AssertionError("search modified source");
    try { ShadowReplay.INSTANCE.rollout(checkpoint,base,List.of(),256,()->true);throw new AssertionError("cancel ignored"); } catch(java.util.concurrent.CancellationException expected) {}
    System.out.println("Deterministic production shadow replay/search/isolation/cancellation passed; "+result.getMessage());
  }
}

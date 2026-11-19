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

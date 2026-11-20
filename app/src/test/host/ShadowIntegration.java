import com.enhikyy.girassol.core.*;
import com.enhikyy.girassol.cognition.*;
import java.util.*;
public class ShadowIntegration {
  public static void main(String[] args) {
    WorldActor original=new WorldActor();
    Checkpoint checkpoint=original.checkpoint();
    var base=Collections.nCopies(8,0.0);
    var genome=new CognitivePolicyGenome().mutate(Collections.nCopies(8,0.0));
    var one=ShadowReplay.INSTANCE.rollout(checkpoint,base,List.of(),256,()->false,genome);
    var two=ShadowReplay.INSTANCE.rollout(checkpoint,base,List.of(),256,()->false,genome);
    WorldActor live=new WorldActor(checkpoint,WorldActor.CURRENT_RULES_VERSION);
    List<ActionRecord> actions=List.of();
    for(int i=0;i<256;i++) {
      var input=NativeMind.INSTANCE.evaluate(live.getState(),actions,base,ThermalTier.NONE_OR_LIGHT,genome);
      var spatial=com.enhikyy.girassol.physics.NativeSpatial.INSTANCE.advance(live.getState(),live.intentions(null,input));
      var transition=live.step(null,input,spatial);
      if(transition.getEvent().getVersion()!=WorldActor.CURRENT_RULES_VERSION) throw new AssertionError("wrong envelope version");
      if(transition.getState().getEconomy()==null || !transition.getState().getEconomy().valid(transition.getState())) throw new AssertionError("missing physical economy");
      for(var entry:transition.getEvent().getTransfers()) if(!entry.valid()) throw new AssertionError("invalid ledger");
      actions=transition.getEvent().getActions();
    }
    if(!one.getCheckpoint().equals(live.checkpoint())) throw new AssertionError("shadow differs from live rules");
    if(!one.equals(two)) throw new AssertionError("non deterministic shadow");
    if(!checkpoint.equals(original.checkpoint())) throw new AssertionError("source modified");
    var trials=new ArrayList<RsiTrial>();
    var result=ShadowReplay.INSTANCE.search(checkpoint,List.of(),()->false,(done,total)->{ System.out.println("Evaluated "+done+"/"+total);return kotlin.Unit.INSTANCE; }, trial->{trials.add(trial);return kotlin.Unit.INSTANCE;});
    var proposed=new HashSet<String>();
    var terminal=new HashSet<String>();
    for(var trial:trials) {
      if(!trial.valid()) throw new AssertionError("invalid trial evidence");
      if(trial.getStage().equals("PROPOSED")) {
        if(!proposed.add(trial.getId())) throw new AssertionError("duplicate proposal");
      } else {
        if(!proposed.contains(trial.getId())) throw new AssertionError("evaluation before proposal");
        if(!trial.getStage().equals("TRAINED") && !terminal.add(trial.getId())) throw new AssertionError("duplicate terminal");
      }
    }
    if(proposed.size()!=20 || !terminal.equals(proposed)) throw new AssertionError("missing mutation/rejection audit");
    if(!one.getSafety().valid() || one.getSafety().getObservations()==0 || one.getSafety().getMeanFreeEnergy()<=0) throw new AssertionError("missing forecasts");
    if(result.getEvaluated()<21) throw new AssertionError("missing candidates");
    if(result.getRevision()!=null && !result.getRevision().valid(checkpoint.getState().getSeed(),0)) throw new AssertionError("unsafe promotion");
    if(!checkpoint.equals(original.checkpoint())) throw new AssertionError("search modified source");
    try { ShadowReplay.INSTANCE.rollout(checkpoint,base,List.of(),256,()->true);throw new AssertionError("cancel ignored"); } catch(java.util.concurrent.CancellationException expected) {}
    var cancelled=new ArrayList<RsiTrial>();
    var cancelFlag=new java.util.concurrent.atomic.AtomicBoolean(false);
    var interrupted=ShadowReplay.INSTANCE.search(checkpoint,List.of(),()->cancelFlag.get(),(done,total)->kotlin.Unit.INSTANCE,trial->{
      cancelled.add(trial); if(trial.getStage().equals("PROPOSED")) cancelFlag.set(true); return kotlin.Unit.INSTANCE;
    });
    if(interrupted.getRevision()!=null || cancelled.size()!=2 || !cancelled.get(1).getStage().equals("CANCELLED")) throw new AssertionError("cancel lost proposal");
    try {
      ShadowReplay.INSTANCE.search(checkpoint,List.of(),()->false,(done,total)->kotlin.Unit.INSTANCE,trial->{throw new IllegalStateException("write rejected");});
      throw new AssertionError("write failure ignored");
    } catch(IllegalStateException expected) { if(!expected.getMessage().equals("write rejected")) throw expected; }
    System.out.println("Deterministic production shadow replay/search/isolation/cancellation passed; "+result.getMessage());
  }
}

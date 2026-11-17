package tuning;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.datastructure.*;
import java.nio.file.*;
import java.util.*;
/** Two real connectivity requests only; never reported as an effectiveness experiment. */
public final class GuidanceProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("Usage: GuidanceProbe INPUT CONFIG NEW_OUTPUT");
        Path input=Paths.get(args[0]),directory=Paths.get(args[2]);if(Files.exists(directory))throw new IllegalArgumentException("Choose a new output directory");
        ExperimentConfig cfg=FilesIO.read(Paths.get(args[1]),ExperimentConfig.class);ModelLoader.load(input);
        int[] genes=new int[GA.DiagramParser.numofUC+GA.DiagramParser.numofEntity];for(int i=0;i<genes.length;i++)genes[i]=i%2;
        if(GA.DiagramParser.instancegetRelatedEntity!=null)for(List<Integer> group:GA.DiagramParser.instancegetRelatedEntity)for(int e:group)genes[GA.DiagramParser.numofUC+e]=genes[GA.DiagramParser.numofUC+group.get(0)];
        Chromosome chromosome=SearchRunner.chromosome(genes);com.debacharya.nsgaii.Service.calculateObjectiveValues(chromosome,SearchRunner.objectives());
        RunResult.SolutionRecord base=SearchRunner.record(chromosome);int metric=1;GuidanceEvidence evidence=new GuidanceEvidence(FilesIO.inputHash(input));
        Files.createDirectories(directory);evidence.save(directory);JSONObject out=new JSONObject();out.put("purpose","Connectivity only, not a quality/effectiveness experiment");
        if(metric>=0){List<JSONObject> tasks=evidence.tasks(metric,base,cfg.guidance,evidence.diagnostics(base));if(!tasks.isEmpty()){JSONObject response=GuidanceClients.generate(cfg.guidance,tasks.get(0));FilesIO.write(directory.resolve("llm-connectivity.json"),response);out.put("llmStatus",response.get("status"));out.put("llmError",response.get("error"));out.put("llmTokens",response.get("totalTokens"));}}
        JSONObject state=new JSONObject();JSONObject choices=new JSONObject();choices.put("KEEP",Collections.singletonMap("meaning","Connectivity test only; keep the current actual partition"));state.put("choices",choices);state.put("base",base);state.put("purpose","Connectivity test; no new candidate or effectiveness claim");
        JSONObject response=GuidanceClients.choose(cfg.jev,state);FilesIO.write(directory.resolve("jev-connectivity.json"),response);out.put("jevStatus",response.get("status"));out.put("jevError",response.get("error"));out.put("jevTokens",response.get("totalTokens"));FilesIO.write(directory.resolve("connectivity-summary.json"),out);System.out.println(out.toJSONString());
    }
}

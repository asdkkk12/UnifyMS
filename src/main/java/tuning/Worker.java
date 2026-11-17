package tuning;
import com.alibaba.fastjson2.*;
import java.nio.file.*;
import java.util.*;
/** Always invoked in a fresh process to isolate the legacy static parsers. */
public final class Worker {
    public static void main(String[] args)throws Exception {
        Path request=Paths.get(args[0]),output=Paths.get(args[1]);
        JSONObject job=FilesIO.object(request);long start=System.nanoTime();
        try {
            Path input=Paths.get(job.getString("input"));
            String hash=FilesIO.inputHash(input);
            if(!hash.equals(job.getString("inputHash")))throw new IllegalArgumentException("Input changed before trial");
            List<String>warnings=ModelLoader.load(input);
            double inputMillis=(System.nanoTime()-start)/1e6;
            RunResult result;
            if(job.containsKey("chromosomes")) {
                result=new RunResult();result.config=job.getObject("config",RunConfig.class);
                JSONArray chromosomes=job.getJSONArray("chromosomes");result.populationSize=chromosomes.size();
                Map<String,RunResult.SolutionRecord> unique=new TreeMap<>();
                for(int i=0;i<chromosomes.size();i++) {
                    int[] genes=chromosomes.getObject(i,int[].class);
                    if(!SearchRunner.valid(genes)){result.invalidCount++;continue;}
                    java.util.List<com.debacharya.nsgaii.datastructure.IntegerAllele> alleles=new java.util.ArrayList<>();
                    for(int gene:genes)alleles.add(new com.debacharya.nsgaii.datastructure.IntegerAllele(gene));
                    com.debacharya.nsgaii.datastructure.Chromosome c=new com.debacharya.nsgaii.datastructure.Chromosome(alleles);
                    com.debacharya.nsgaii.Service.calculateObjectiveValues(c,SearchRunner.objectives());
                    RunResult.SolutionRecord row=SearchRunner.record(c);
                    boolean finite=true;for(double v:row.objectives)if(!Double.isFinite(v))finite=false;
                    if(!finite){result.invalidCount++;continue;}
                    result.population.add(row);unique.putIfAbsent(Arrays.toString(row.chromosome),row);result.evaluations++;
                }
                result.validRate=result.populationSize==0?0:(double)result.population.size()/result.populationSize;
                result.validUniqueCount=unique.size();result.front=Quality.front(new ArrayList<>(unique.values()));result.nonDominatedCount=SearchRunner.objectivePointCount(result.front);
            } else {
                RunConfig config=job.getObject("config",RunConfig.class);
                OnlineControlSession online=OnlineControlSession.fromJob(job,config);
                GuidanceSession guidance=GuidanceSession.fromJob(job,config);
                result=new SearchRunner().run(config,online,guidance);
            }
            result.inputHash=hash;result.taskKey=job.getString("taskKey");result.inputMillis=inputMillis;
            result.structure=ModelLoader.structure();result.warnings=warnings;
            if(!hash.equals(FilesIO.inputHash(input)))throw new IllegalArgumentException("Input changed during trial");
            result.workerMillis=(System.nanoTime()-start)/1e6;FilesIO.write(output,result);
        }catch(Exception e){
            Map<String,Object> failure=new LinkedHashMap<>();failure.put("status","failed");failure.put("error",e.toString());
            failure.put("taskKey",job.getString("taskKey"));FilesIO.write(output,failure);throw e;
        }
    }
}

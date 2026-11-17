package tuning;

import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.Service;
import java.nio.file.*;
import java.util.*;
import tuning.RunResult.SolutionRecord;

/** Isolated case-bound historical ablations. Historical evidence is imported, never relabelled as new search. */
public final class LegacyAblationCli {
    private static final List<Long> SEEDS=Arrays.asList(101L,103L,107L,109L,113L,127L,131L,137L,139L,149L);
    private static JSONObject protocol,calibration,selection;
    private static ExperimentConfig config;
    private static Path input;
    private static Quality.Normalization norm;
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
    private static void load(Path file)throws Exception {
        LegacyAblationProtocol.activate(file);protocol=FilesIO.object(file);
        require(protocol.getString("historicalImplementationHash").equals(FilesIO.object(Paths.get(protocol.getString("historicalManifest"))).getString("implementationHash")),"Historical implementation identity mismatch");
        for(Object raw:protocol.getJSONArray("frozenFiles")){
            JSONObject row=(JSONObject)raw;require(FilesIO.digest(Files.readAllBytes(Paths.get(row.getString("path")))).equals(row.getString("sha256")),"Frozen file changed: "+row.getString("path"));
        }
        input=Paths.get(protocol.getString("input"));require(LegacyAblationProtocol.inputHash().equals(FilesIO.inputHash(input)),"Frozen case input identity mismatch");
        config=FilesIO.read(Paths.get(protocol.getString("config")),ExperimentConfig.class);
        calibration=FilesIO.object(Paths.get(protocol.getString("calibration")));selection=FilesIO.object(Paths.get(protocol.getString("selection")));
        require(LegacyAblationProtocol.metricVersion().equals(config.metricVersion)&&LegacyAblationProtocol.metricVersion().equals(calibration.getString("metricVersion")),"Not the historical metric protocol");
        require(LegacyAblationProtocol.inputHash().equals(calibration.getString("inputHash"))&&LegacyAblationProtocol.inputHash().equals(selection.getString("inputHash")),"Historical input mismatch");
        require("success".equals(selection.getString("status"))&&LegacyAblationProtocol.selectedConfig().equals(selection.getString("choice")),"Expected successful frozen case selection");
        require(calibration.getString("binding").equals(selection.getString("binding"))&&FilesIO.hash(calibration.toJSONString()).equals(selection.getString("evidenceHash")),"Historical selection/calibration binding mismatch");
        require(config.calibrationSeeds.equals(Collections.singletonList(11L))&&config.validationSeeds.equals(SEEDS),"Seed protocol changed");
        require(config.candidates.size()==6,"Expected six historical configurations");
        for(RunConfig expected:ExperimentConfig.defaults())require(ExperimentConfig.sameParameters(expected,config.candidate(expected.id)),"Historical configuration table changed");
        require(config.evaluationBudget==0&&config.onlineControl.intervalGenerations==10&&config.guidance.intervalGenerations==10&&config.guidance.lossTolerance==.5,"Window/budget/gate mismatch");
        require(config.onlineControl.gainThreshold==.0002&&config.onlineControl.step==.05&&config.guidance.maxTasks==4&&config.guidance.candidatesPerTask==2&&config.guidance.maxSelected==3,"Historical control policy mismatch");
        require("gpt-4.1-mini-2025-04-14".equals(config.guidance.generator.model)&&"jev-1.14.0".equals(config.jev.model),"Historical models changed");
        require(OnlineJevClient.PROMPT_VERSION.equals(protocol.getString("onlinePromptVersion")),"Probability prompt version mismatch");
        norm=calibration.getObject("normalization",Quality.Normalization.class);OnlineControlSession.validateNormalization(norm);
        ModelLoader.load(input);
    }
    private static SolutionRecord evaluate(int[] genes){
        com.debacharya.nsgaii.datastructure.Chromosome c=SearchRunner.chromosome(genes);Service.calculateObjectiveValues(c,SearchRunner.objectives());return SearchRunner.record(c);
    }
    private static void sameMetrics(SolutionRecord a,SolutionRecord b,String where){
        require(Arrays.equals(a.chromosome,b.chromosome),"Chromosome changed: "+where);
        for(int i=0;i<5;i++)require(Math.abs(a.objectives[i]-b.objectives[i])<1e-12&&Math.abs(a.metrics[i]-b.metrics[i])<1e-12,"Metric changed: "+where+" dimension "+i+" expected "+a.metrics[i]+" got "+b.metrics[i]);
    }
    private static Map<String,Integer> populationSignature(RunResult r){
        Map<String,Integer> rows=new TreeMap<>();for(SolutionRecord s:r.population)rows.merge(Arrays.toString(s.chromosome),1,Integer::sum);return rows;
    }
    private static void verify(Path out)throws Exception {
        long start=System.nanoTime();Path source=Paths.get(protocol.getString("historicalExperiment"));List<RunResult> historical=new ArrayList<>();JSONArray verified=new JSONArray();int populations=0;
        for(String id:Arrays.asList("C0","C1","C2","C3","C4","C5")){
            JSONObject t=calibration.getJSONObject("candidates").getJSONObject(id).getJSONArray("trials").getJSONObject(0);
            Path result=source.resolve("trials").resolve(t.getString("taskKey")).resolve("result.json");RunResult old=FilesIO.read(result,RunResult.class);require(old.config.id.equals(id)&&old.config.seed==11,"Historical calibration configuration mismatch");
            require(old.inputHash.equals(LegacyAblationProtocol.inputHash())&&old.metricVersion.equals(LegacyAblationProtocol.metricVersion()),"Historical trial identity mismatch");
            for(SolutionRecord row:old.population){sameMetrics(row,evaluate(row.chromosome),id+" saved population");populations++;}historical.add(old);
            double hv=Quality.hv(old.front,norm);require(Math.abs(hv-t.getDoubleValue("hv"))<1e-12,"Historical HV mismatch "+id);
            sameMetrics(t.getObject("representative",SolutionRecord.class),Quality.representative(old.front,norm),id+" representative");
            // Search is deterministic and calibration has no model calls; rerun to validate actual operators.
            ModelLoader.load(input);RunResult fresh=new SearchRunner().run(old.config);
            boolean matches=fresh.evaluations==old.evaluations&&fresh.completedGenerations==old.completedGenerations&&populationSignature(fresh).equals(populationSignature(old));
            if(matches){Map<String,SolutionRecord> records=new HashMap<>();for(SolutionRecord s:fresh.population)records.put(Arrays.toString(s.chromosome),s);for(SolutionRecord s:old.population)sameMetrics(s,records.get(Arrays.toString(s.chromosome)),id+" replay metrics");}
            if(!matches){FilesIO.write(out.resolveSibling(id+"-replay.json"),fresh);JSONObject difference=new JSONObject();difference.put("status","failed");difference.put("configuration",id);difference.put("historicalHv",hv);difference.put("replayedHv",Quality.hv(fresh.front,norm));difference.put("historicalUnique",populationSignature(old).size());difference.put("replayedUnique",populationSignature(fresh).size());difference.put("historicalEvaluations",old.evaluations);difference.put("replayedEvaluations",fresh.evaluations);difference.put("historicalGenerations",old.completedGenerations);difference.put("replayedGenerations",fresh.completedGenerations);FilesIO.write(out.resolveSibling("compatibility-failure.json"),difference);}
            require(matches,"Search operator/calibration trace mismatch "+id+"; see compatibility-failure.json and "+id+"-replay.json");
            JSONObject row=new JSONObject();row.put("configuration",id);row.put("populationRows",old.population.size());row.put("hv",hv);row.put("searchMatched",true);row.put("searchMillis",fresh.searchMillis);verified.add(row);
        }
        require(GuidanceSession.hash(Quality.fit(historical)).equals(GuidanceSession.hash(norm)),"Frozen normalization recomputation mismatch");
        GuidanceEvidence model=new GuidanceEvidence(LegacyAblationProtocol.inputHash());int tasks=0,choices=0,probabilities=0;
        for(Object raw:protocol.getJSONArray("auditWindows")){
            Path path=Paths.get(raw.toString());JSONObject w=FilesIO.object(path);SolutionRecord base=w.getObject("base",SolutionRecord.class);JSONObject d=model.diagnostics(base);int metric=GuidanceEvidence.METRICS.indexOf(w.getString("selectedMetric"));
            List<JSONObject> generated=model.tasks(metric,base,config.guidance,d);
            require(JSON.toJSON(generated).equals(w.getJSONArray("tasks")),"Historical diagnostic/task template mismatch: "+path);tasks+=generated.size();
            for(Object c:w.getJSONArray("generationCalls")){
                JSONObject request=((JSONObject)c).getJSONObject("request");require(request.getJSONArray("messages").getJSONObject(0).getString("content").equals(LegacyAblationProtocol.generationInstructions()),"Historical generator instructions mismatch");
            }
            for(Object c:w.getJSONArray("selectionCalls")){
                JSONObject call=(JSONObject)c,request=call.getJSONObject("response").getJSONObject("request");if(request==null)continue;
                require(GuidanceClients.choiceRequest(call.getJSONObject("evidence"),config.jev.model).equals(request),"Historical Choice request mismatch");choices++;
            }
        }
        for(Object raw:protocol.getJSONArray("probabilityRequests")){
            JSONObject r=FilesIO.object(Paths.get(raw.toString())),request=r.getJSONObject("request");if(request==null)continue;
            require(OnlineJevClient.request(request.getJSONObject("state"),config.jev.model).equals(request),"Historical probability request mismatch");probabilities++;
        }
        JSONObject report=new JSONObject();report.put("status","success");report.put("protocolVersion",LegacyAblationProtocol.protocolVersion());report.put("metricVersion",LegacyAblationProtocol.metricVersion());report.put("historicalImplementationHash",protocol.get("historicalImplementationHash"));report.put("compatibilityImplementationHash",FilesIO.inputHash(Paths.get("src/main/java")));report.put("calibrations",verified);report.put("recomputedPopulationRows",populations);report.put("matchedGenerationTasks",tasks);report.put("matchedChoiceRequests",choices);report.put("matchedProbabilityRequests",probabilities);report.put("validationMillis",(System.nanoTime()-start)/1e6);FilesIO.write(out,report);System.out.println(report.toJSONString());
    }
    private static void run(String arm,long seed,Path directory)throws Exception {
        require(Arrays.asList("without-offline","without-probability","without-guidance").contains(arm),"Only three ablation arms are allowed; Full is pending");require(SEEDS.contains(seed),"Unknown formal seed");
        Path verification=Paths.get(protocol.getString("verification"));JSONObject checked=FilesIO.object(verification);String code=FilesIO.inputHash(Paths.get("src/main/java"));require("success".equals(checked.getString("status"))&&code.equals(checked.getString("compatibilityImplementationHash")),"Compatibility verification missing/stale");
        Files.createDirectories(directory);require(!Files.exists(directory.resolve("run.json"))&&!Files.exists(directory.resolve("audit/control-manifest.json"))&&!Files.exists(directory.resolve("guidance/guidance-manifest.json")),"Choose a fresh trial directory");
        RunConfig c=JSON.parseObject(JSON.toJSONString(config.candidate("without-offline".equals(arm)?"C0":LegacyAblationProtocol.selectedConfig())),RunConfig.class);c.seed=seed;c.evaluationBudget=0;
        OnlineControlOptions online=JSON.parseObject(JSON.toJSONString(config.onlineControl),OnlineControlOptions.class);online.mode="without-probability".equals(arm)?"off":"jev";online.monitor=true;
        GuidanceOptions guidance=JSON.parseObject(JSON.toJSONString(config.guidance),GuidanceOptions.class);guidance.mode="without-guidance".equals(arm)?"off":"jev";
        JSONObject context=new JSONObject();context.put("inputHash",LegacyAblationProtocol.inputHash());context.put("implementationHash",code);context.put("calibrationSha256",FilesIO.digest(Files.readAllBytes(Paths.get(protocol.getString("calibration")))));context.put("legacyProtocol",LegacyAblationProtocol.protocolVersion());
        JSONObject manifest=new JSONObject();manifest.put("arm",arm);manifest.put("seed",seed);manifest.put("config",c);manifest.put("protocolVersion",LegacyAblationProtocol.protocolVersion());manifest.put("protocolHash",GuidanceSession.hash(protocol));manifest.put("historicalImplementationHash",protocol.get("historicalImplementationHash"));manifest.put("implementationHash",code);manifest.put("normalization",norm);manifest.put("onlineControl",online);manifest.put("guidance",guidance);manifest.put("configurationOrigin","without-offline".equals(arm)?"default-C0":"historical-JEV-"+LegacyAblationProtocol.selectedConfig());FilesIO.write(directory.resolve("manifest.json"),manifest);
        long start=System.nanoTime();OnlineControlSession control=new OnlineControlSession(online,norm,c,directory.resolve("audit"),context,"jev".equals(online.mode)?new OnlineJevClient(config.jev,online.requestTimeoutMillis,System.getenv("TYPESAFE_API_KEY")):null);
        GuidanceSession guides=guidance.enabled()?new GuidanceSession(guidance,norm,c,directory.resolve("guidance"),context,new GuidanceEvidence(LegacyAblationProtocol.inputHash()),t->GuidanceClients.generate(guidance,t),e->GuidanceClients.choose(config.jev,e)):null;
        RunResult result=new SearchRunner().run(c,control,guides);result.inputHash=LegacyAblationProtocol.inputHash();result.taskKey=GuidanceSession.hash(manifest);result.structure=ModelLoader.structure();result.workerMillis=(System.nanoTime()-start)/1e6;
        JSONObject out=JSON.parseObject(JSON.toJSONString(Quality.evaluate(result,norm)));out.put("arm",arm);out.put("run",result);out.put("protocolVersion",LegacyAblationProtocol.protocolVersion());out.put("protocolHash",GuidanceSession.hash(protocol));out.put("configurationOrigin",manifest.get("configurationOrigin"));FilesIO.write(directory.resolve("run.json"),out);
        require(result.completedGenerations==c.generations,"Incomplete nominal generations");
        if("without-probability".equals(arm))require(((Number)result.onlineControl.get("requestCount")).intValue()==0,"Disabled probability JEV was called");
        if("without-guidance".equals(arm))require(result.guidance==null&&result.guidanceEvaluations==0,"Disabled guidance produced candidates");
        System.out.println(arm+" seed="+seed+" HV="+out.get("hv"));
    }
    public static void main(String[] args)throws Exception {
        require(args.length>=3,"Usage: LegacyAblationCli verify PROTOCOL REPORT | run PROTOCOL ARM SEED OUTPUT");load(Paths.get(args[1]));
        try {if("verify".equals(args[0]))verify(Paths.get(args[2]));else if("run".equals(args[0])&&args.length==5)run(args[2],Long.parseLong(args[3]),Paths.get(args[4]));else throw new IllegalArgumentException("Unknown legacy command");}
        finally{LegacyAblationProtocol.reset();}
    }
}

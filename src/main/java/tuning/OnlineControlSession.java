package tuning;

import GA.Mycross;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.datastructure.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-run observer/controller. Never evaluates objectives or consumes SearchRandom. */
public final class OnlineControlSession {
    public static final String VERSION="online-novelty-gain-history-v1";
    public static final String ALGORITHM="seeded-legacy-nsga2-online-v1";
    public static final List<String> ACTIONS=Collections.unmodifiableList(Arrays.asList("INCREASE","KEEP","DECREASE"));
    public interface DecisionClient {JSONObject decide(JSONObject evidence)throws Exception;}
    private final OnlineControlOptions options;
    private final Quality.Normalization normalization;
    private final Path audit;
    private final DecisionClient client;
    private final String contextHash;
    private final List<JSONObject> window=new ArrayList<>(),history=new ArrayList<>(),decisions=new ArrayList<>(),replay=new ArrayList<>();
    private double probability;
    private final long evaluationLimit;
    private final int populationSize;
    private final double initialCrossP;
    public double monitoringMillis,controlMillis,auditMillis;
    private int generations,requestCount,fallbackCount,replayIndex;
    private long knownTokens;
    private boolean usageComplete=true;
    private double responseMillis;

    public static String hash(Object object){return FilesIO.hash(JSON.toJSONString(sorted(object)));}
    private static Object sorted(Object value) {
        if(value instanceof Map){Map<String,Object> out=new TreeMap<>();for(Object key:((Map<?,?>)value).keySet())out.put(key.toString(),sorted(((Map<?,?>)value).get(key)));return out;}
        if(value instanceof Collection){List<Object> out=new ArrayList<>();for(Object v:(Collection<?>)value)out.add(sorted(v));return out;}
        return value;
    }
    public static void validateNormalization(Quality.Normalization n) {
        if(n==null || n.min==null || n.max==null || n.reference==null || n.zeroSpan==null || n.min.length!=5 || n.max.length!=5 || n.reference.length!=5 || n.zeroSpan.size()!=5)
            throw new IllegalArgumentException("Frozen five-dimensional normalization required");
        for(int i=0;i<5;i++)if(!Double.isFinite(n.min[i]) || !Double.isFinite(n.max[i]) || !Double.isFinite(n.reference[i]) || n.max[i]<n.min[i] || n.zeroSpan.get(i)==null || n.zeroSpan.get(i)!=(n.min[i]==n.max[i]))
            throw new IllegalArgumentException("Invalid frozen normalization");
    }
    public static OnlineControlSession fromJob(JSONObject job,RunConfig config)throws Exception {
        OnlineControlOptions o=job.getObject("onlineControl",OnlineControlOptions.class);
        if(o==null)return null;o.validate();if(!o.enabled())return null;
        String file=job.getString("calibrationFile"),sha=job.getString("calibrationSha256"),audit=job.getString("auditDirectory");
        if(file==null || sha==null || audit==null)throw new IllegalArgumentException("Online run requires calibration source, hash and audit directory");
        Path source=Paths.get(file);if(!FilesIO.digest(Files.readAllBytes(source)).equals(sha))throw new IllegalArgumentException("Calibration file SHA-256 mismatch");
        JSONObject cal=FilesIO.object(source);
        if(!Objects.equals(cal.getString("inputHash"),job.getString("inputHash")) || !SearchRunner.METRICS.equals(cal.getString("metricVersion")) || !SearchRunner.VERSION.equals(cal.getString("algorithmVersion")))
            throw new IllegalArgumentException("Calibration input/metric/algorithm mismatch");
        if(cal.getJSONArray("calibrationSeeds")==null || cal.getJSONArray("calibrationSeeds").toJavaList(Long.class).contains(config.seed))throw new IllegalArgumentException("Missing calibration seeds or formal seed overlaps calibration");
        Quality.Normalization n=job.getObject("normalization",Quality.Normalization.class);validateNormalization(n);
        if(!hash(JSON.parseObject(JSON.toJSONString(n))).equals(hash(cal.getJSONObject("normalization"))))throw new IllegalArgumentException("Normalization does not match frozen calibration");
        JSONObject context=new JSONObject();context.put("inputHash",job.get("inputHash"));context.put("calibrationSha256",sha);context.put("implementationHash",job.get("implementationHash"));
        ExperimentConfig.JevOptions jev=job.getObject("jev",ExperimentConfig.JevOptions.class);
        DecisionClient client="jev".equals(o.mode)?new OnlineJevClient(jev==null?new ExperimentConfig.JevOptions():jev,o.requestTimeoutMillis,System.getenv("TYPESAFE_API_KEY")):null;
        return new OnlineControlSession(o,n,config,Paths.get(audit),context,client);
    }
    public OnlineControlSession(OnlineControlOptions o,Quality.Normalization n,RunConfig cfg,Path audit,JSONObject context,DecisionClient client)throws Exception {
        o.validate();validateNormalization(n);cfg.validate();
        if(cfg.crossoverProbability<o.minProbability || cfg.crossoverProbability>o.maxProbability)throw new IllegalArgumentException("Initial p_var outside online bounds");
        this.options=JSON.parseObject(JSON.toJSONString(o),OnlineControlOptions.class);
        this.normalization=JSON.parseObject(JSON.toJSONString(n),Quality.Normalization.class);
        this.audit=audit;this.client=client;this.probability=cfg.crossoverProbability;
        this.populationSize=cfg.populationSize;this.initialCrossP=cfg.crossP;this.evaluationLimit=cfg.evaluationBudget>0?cfg.evaluationBudget:(3L*cfg.generations+1)*cfg.populationSize;
        JSONObject binding=new JSONObject();binding.put("context",context);binding.put("config",cfg);binding.put("normalization",JSON.parseObject(JSON.toJSONString(n)));
        JSONObject policy=JSON.parseObject(JSON.toJSONString(o));policy.remove("mode");policy.remove("monitor");policy.remove("replayFile");
        binding.put("policy",policy);binding.put("policyVersion",VERSION);binding.put("promptVersion",OnlineJevClient.PROMPT_VERSION);contextHash=hash(binding);
        if(Files.exists(audit.resolve("control-manifest.json")))throw new IllegalArgumentException("Audit directory already contains a run; use a new output directory");
        Files.createDirectories(audit);
        Files.createFile(audit.resolve("generation-stats.jsonl"));
        Files.createFile(audit.resolve("control-decisions.jsonl"));
        if("replay".equals(o.mode)) {
            for(String line:Files.readAllLines(Paths.get(o.replayFile),StandardCharsets.UTF_8))if(!line.trim().isEmpty())replay.add(JSON.parseObject(line));
        }
        JSONObject manifest=new JSONObject();manifest.put("policyVersion",VERSION);manifest.put("promptVersion",OnlineJevClient.PROMPT_VERSION);
        manifest.put("contextHash",contextHash);manifest.put("binding",binding);manifest.put("mode",o.mode);
        write(audit.resolve("control-manifest.json"),manifest);
    }
    /** Lightweight immutable copies: do not call SearchRunner.record(), which computes interfaces. */
    public static List<RunResult.SolutionRecord> snapshot(Population population) {
        Map<String,RunResult.SolutionRecord> unique=new TreeMap<>();
        for(Chromosome c:population.getPopulace()) {
            RunResult.SolutionRecord r=new RunResult.SolutionRecord();r.chromosome=SearchRunner.canonical(SearchRunner.genes(c));r.objectives=new double[5];
            for(int j=0;j<5;j++)r.objectives[j]=c.getObjectiveValue(j);
            unique.putIfAbsent(Arrays.toString(r.chromosome),r);
        }
        return new ArrayList<>(unique.values());
    }
    public static JSONObject statistics(List<RunResult.SolutionRecord> parent,List<RunResult.SolutionRecord> child,Quality.Normalization n) {
        Set<String> p=new HashSet<>(),q=new HashSet<>();for(RunResult.SolutionRecord r:parent)p.add(Arrays.toString(SearchRunner.canonical(r.chromosome)));
        for(RunResult.SolutionRecord r:child)q.add(Arrays.toString(SearchRunner.canonical(r.chromosome)));
        Set<String> novel=new HashSet<>(q);novel.removeAll(p);
        // A point identity is sufficient for HV, but genotype identity is used for novelty.
        Map<String,RunResult.SolutionRecord> points=new TreeMap<>();for(RunResult.SolutionRecord r:parent)points.putIfAbsent(Arrays.toString(r.objectives),r);
        double hp=Quality.hv(Quality.front(new ArrayList<>(points.values())),n);
        for(RunResult.SolutionRecord r:child)points.putIfAbsent(Arrays.toString(r.objectives),r);
        double hu=Quality.hv(Quality.front(new ArrayList<>(points.values())),n),raw=hu-hp;
        double tolerance=1e-10*Math.max(1,Math.max(Math.abs(hp),Math.abs(hu)));
        boolean valid=!q.isEmpty() && Double.isFinite(hp) && Double.isFinite(hu) && Double.isFinite(raw) && raw>=-tolerance;
        JSONObject out=new JSONObject();out.put("parentUniqueCount",p.size());out.put("offspringUniqueCount",q.size());out.put("novelUniqueCount",novel.size());
        out.put("novelty",q.isEmpty()?null:(double)novel.size()/q.size());out.put("parentHv",hp);out.put("unionHv",hu);
        out.put("rawGain",raw);out.put("gain",valid?Math.max(0,raw):null);out.put("tolerance",tolerance);out.put("valid",valid);
        if(!valid)out.put("warning",q.isEmpty()?"No unique offspring":"Invalid/nonfinite/negative HV contribution");return out;
    }
    public void observe(Population parent,Population child,int generation) throws IOException {
        long start=System.nanoTime();
        JSONObject row=statistics(snapshot(parent),snapshot(child),normalization);
        row.put("generation",generation);row.put("probability",probability);row.put("effectiveFloatProbability",(double)(float)probability);generations=generation;window.add(row);
        monitoringMillis+=(System.nanoTime()-start)/1e6;
        append(audit.resolve("generation-stats.jsonl"),row);
    }
    public static String rule(JSONObject evidence,OnlineControlOptions o) {
        JSONObject w=evidence.getJSONObject("currentWindow");
        if(!w.getBooleanValue("valid"))return "KEEP";
        if(w.getDoubleValue("meanGain")>o.gainThreshold)return "KEEP";
        return w.getDoubleValue("meanNovelty")<o.noveltyThreshold?"INCREASE":"DECREASE";
    }
    public double apply(String action) {
        if(!ACTIONS.contains(action))throw new IllegalArgumentException("Unknown action");
        double next=probability+("INCREASE".equals(action)?options.step:"DECREASE".equals(action)?-options.step:0);
        return Math.max(options.minProbability,Math.min(options.maxProbability,next));
    }
    public void afterSelection(int generation,boolean canContinue,Mycross crossover)throws Exception {
        afterSelection(generation,canContinue,crossover,(3L*generation+1)*populationSize,false);
    }
    public void afterSelection(int generation,boolean canContinue,Mycross crossover,long actualEvaluations,boolean includesGuides)throws Exception {
        if(!canContinue || generation%options.intervalGenerations!=0)return;
        JSONObject current=new JSONObject();current.put("startGeneration",generation-options.intervalGenerations+1);current.put("endGeneration",generation);
        current.put("probability",probability);current.put("valid",window.size()==options.intervalGenerations);
        double novelty=0,gain=0;
        for(JSONObject row:window){if(!row.getBooleanValue("valid"))current.put("valid",false);else{novelty+=row.getDoubleValue("novelty");gain+=row.getDoubleValue("gain");}}
        current.put("sampleCount",window.size());
        current.put("meanNovelty",current.getBooleanValue("valid")?novelty/window.size():null);
        current.put("meanGain",current.getBooleanValue("valid")?gain/window.size():null);
        JSONObject evidence=new JSONObject();evidence.put("policyVersion",VERSION);evidence.put("promptVersion",OnlineJevClient.PROMPT_VERSION);
        evidence.put("contextHash",contextHash);evidence.put("currentGeneration",generation);evidence.put("currentProbability",probability);
        evidence.put("evaluationsAfterSelection",actualEvaluations);
        evidence.put("candidateSource",includesGuides?"original offspring plus guidance candidates; effects are joint":"original offspring");
        evidence.put("remainingEvaluations",includesGuides?null:Math.max(0,evaluationLimit-actualEvaluations));
        evidence.put("evaluationBudget",includesGuides?null:evaluationLimit);
        evidence.put("currentWindow",current);evidence.put("noveltyThreshold",options.noveltyThreshold);evidence.put("gainThreshold",options.gainThreshold);
        evidence.put("fixedCrossP",initialCrossP);evidence.put("populationSize",populationSize);
        evidence.put("normalization",JSON.parseObject(JSON.toJSONString(normalization)));
        evidence.put("objectiveOrder",Arrays.asList("-IFN","CHD","CMQ","Instability","UCM"));
        evidence.put("normalizationHash",hash(JSON.parseObject(JSON.toJSONString(normalization))));evidence.put("gainInterpretation","Nonnegative pre-truncation marginal coverage, not degradation");
        JSONArray recent=new JSONArray();for(int i=Math.max(0,history.size()-(options.historyWindows-1));i<history.size();i++)recent.add(history.get(i));recent.add(current);evidence.put("history",recent);
        JSONObject actions=new JSONObject();for(String action:ACTIONS){JSONObject target=new JSONObject();target.put("nextProbability",apply(action));actions.put(action,target);}evidence.put("actions",actions);
        String evidenceHash=hash(evidence);JSONObject decision=new JSONObject();decision.put("generation",generation);decision.put("effectiveGeneration",generation+1);
        decision.put("evidenceHash",evidenceHash);decision.put("contextHash",contextHash);decision.put("evidence",evidence);decision.put("mode",options.mode);
        String action="KEEP";
        if("replay".equals(options.mode)) {
            if(replayIndex>=replay.size())throw new IllegalStateException("Replay missing decision at generation "+generation);
            JSONObject old=replay.get(replayIndex++);
            if(old.getIntValue("generation")!=generation || !contextHash.equals(old.getString("contextHash")) || !evidenceHash.equals(old.getString("evidenceHash")))
                throw new IllegalStateException("Replay evidence mismatch at generation "+generation);
            action=old.getString("action");if(!ACTIONS.contains(action))throw new IllegalStateException("Invalid replay action");
            decision.put("replaySource",options.replayFile);
        } else if(!current.getBooleanValue("valid")) {
            decision.put("fallback","Invalid observation window");fallbackCount++;
        } else if("rule".equals(options.mode))action=rule(evidence,options);
        else if("jev".equals(options.mode)) {
            long start=System.nanoTime();JSONObject response;
            try{response=client==null?null:client.decide(JSON.parseObject(evidence.toJSONString()));}
            catch(Exception ex){response=new JSONObject();response.put("status","failed");response.put("error",ex.getClass().getSimpleName());}
            controlMillis+=(System.nanoTime()-start)/1e6;
            if(response==null){response=new JSONObject();response.put("status","failed");response.put("error","Missing client response");}
            if(!response.containsKey("attempted") || response.getBooleanValue("attempted"))requestCount++;
            write(audit.resolve("requests").resolve(String.format("generation-%06d.json",generation)),response);
            if(response.get("totalTokens")==null)usageComplete=false;else knownTokens+=response.getLongValue("totalTokens");
            responseMillis+=response.getDoubleValue("responseMillis");
            if("success".equals(response.getString("status")) && ACTIONS.contains(response.getString("choice")))action=response.getString("choice");
            else{fallbackCount++;decision.put("fallback","JEV request failed or invalid Choice");}
            decision.put("requestFile","requests/"+String.format("generation-%06d.json",generation));
        }
        double before=probability;probability=apply(action);crossover.setTriggerProbability(probability);
        decision.put("action",action);decision.put("probabilityBefore",before);decision.put("probabilityAfter",probability);
        append(audit.resolve("control-decisions.jsonl"),decision);decisions.add(decision);
        // Copy before adding the actual action; evidenceHash always binds the pre-decision state.
        JSONObject completed=JSON.parseObject(current.toJSONString());completed.put("action",action);completed.put("probabilityAfter",probability);history.add(completed);window.clear();
    }
    public Map<String,Object> finish()throws IOException {
        if("replay".equals(options.mode) && replayIndex!=replay.size())throw new IllegalStateException("Replay contains unused decisions");
        JSONObject summary=new JSONObject();summary.put("mode",options.mode);summary.put("status",fallbackCount>0?"degraded":"success");summary.put("policyVersion",VERSION);summary.put("promptVersion",OnlineJevClient.PROMPT_VERSION);
        summary.put("contextHash",contextHash);summary.put("auditDirectory",audit.toAbsolutePath().toString());summary.put("observedGenerations",generations);
        summary.put("decisionCount",decisions.size());summary.put("requestCount",requestCount);summary.put("fallbackCount",fallbackCount);summary.put("finalProbability",probability);
        summary.put("knownTokens",knownTokens);summary.put("usageComplete",usageComplete);summary.put("totalTokens",usageComplete?knownTokens:null);summary.put("totalResponseMillis",responseMillis);
        summary.put("monitoringMillis",monitoringMillis);summary.put("controlMillis",controlMillis);summary.put("auditMillis",auditMillis);
        write(audit.resolve("control-summary.json"),summary);
        StringBuilder md=new StringBuilder("# 在线变异控制记录\n\n此记录不证明 JEV 改善质量。Gain 为截断前新增覆盖，不是退化率。\n\n|完成代数|生效代数|动作|调整前概率|调整后概率|\n|---|---|---|---|---|\n");
        for(JSONObject d:decisions)md.append('|').append(d.get("generation")).append('|').append(d.get("effectiveGeneration")).append('|').append(d.get("action")).append('|').append(d.get("probabilityBefore")).append('|').append(d.get("probabilityAfter")).append("|\n");
        Files.write(audit.resolve("control-analysis.md"),md.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder curve=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"760\" height=\"290\" viewBox=\"0 0 760 290\"><rect width=\"760\" height=\"290\" fill=\"white\"/><path d=\"M50 20V240H730\" fill=\"none\" stroke=\"#64748b\"/><text x=\"50\" y=\"275\" font-family=\"sans-serif\" font-size=\"14\">generation</text><text x=\"55\" y=\"16\" font-family=\"sans-serif\" font-size=\"14\">p_var (0..1)</text><polyline fill=\"none\" stroke=\"#ea580c\" stroke-width=\"3\" points=\"");
        double initial=decisions.isEmpty()?probability:decisions.get(0).getDoubleValue("probabilityBefore");
        curve.append("50,").append(240-210*initial).append(' ');
        for(JSONObject d:decisions){double x=50+680.0*d.getIntValue("generation")/Math.max(1,generations);curve.append(x).append(',').append(240-210*d.getDoubleValue("probabilityBefore")).append(' ').append(x).append(',').append(240-210*d.getDoubleValue("probabilityAfter")).append(' ');}
        curve.append("730,").append(240-210*probability).append("\"/></svg>");
        Files.write(audit.resolve("probability-trajectory.svg"),curve.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject files=new JSONObject();
        try(java.util.stream.Stream<Path> stream=Files.walk(audit)) {
            for(Path p:(Iterable<Path>)stream.filter(Files::isRegularFile)::iterator)
                files.put(audit.relativize(p).toString(),FilesIO.digest(Files.readAllBytes(p)));
        }
        FilesIO.write(audit.resolve("audit-files.json"),files);
        summary.put("auditIndexSha256",FilesIO.digest(Files.readAllBytes(audit.resolve("audit-files.json"))));
        return summary;
    }
    public static boolean auditComplete(JSONObject summary) {
        try {
            if(summary==null || summary.getString("auditDirectory")==null)return false;
            Path root=Paths.get(summary.getString("auditDirectory")),index=root.resolve("audit-files.json");
            if(!FilesIO.digest(Files.readAllBytes(index)).equals(summary.getString("auditIndexSha256")))return false;
            JSONObject files=FilesIO.object(index);
            for(String name:files.keySet()) {
                Path p=root.resolve(name).normalize();
                if(!p.startsWith(root) || !FilesIO.digest(Files.readAllBytes(p)).equals(files.getString(name)))return false;
            }
            return files.containsKey("generation-stats.jsonl") && files.containsKey("control-summary.json");
        }catch(Exception e){return false;}
    }
    private void append(Path file,Object object)throws IOException {long start=System.nanoTime();Files.write(file,(JSON.toJSONString(object)+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);auditMillis+=(System.nanoTime()-start)/1e6;}
    private void write(Path file,Object object)throws IOException {long start=System.nanoTime();FilesIO.write(file,object);auditMillis+=(System.nanoTime()-start)/1e6;}
}

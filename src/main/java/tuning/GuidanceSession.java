package tuning;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.datastructure.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import tuning.RunResult.SolutionRecord;

/** Window-boundary candidate proposals, actual evaluation, sequential Choice and replay. */
public final class GuidanceSession {
    public static String hash(Object value){return OnlineControlSession.hash(JSON.parse(JSON.toJSONString(value,JSONWriter.Feature.WriteNulls)));}
    public static final String VERSION="nominal-10gen-llm-consistency-batch-v1";
    public interface Generator {JSONObject generate(JSONObject task)throws Exception;}
    public interface Chooser {JSONObject choose(JSONObject evidence)throws Exception;}
    public interface Evaluator {SolutionRecord evaluate(int[] genes);}
    private final GuidanceOptions options;
    private final Quality.Normalization normalization;
    private final GuidanceEvidence model;
    private final Path directory;
    private final Generator generator;private final Chooser chooser;
    private final String contextHash;
    private final List<JSONObject> windows=new ArrayList<>(),replay=new ArrayList<>();
    private final JSONObject llmStats=stats(),jevStats=stats();
    private SolutionRecord reference;
    public long candidateEvaluations;
    public double millis;
    private int replayIndex;
    private final int populationSize;
    public static GuidanceSession fromJob(JSONObject job,RunConfig config)throws Exception {
        GuidanceOptions o=job.getObject("guidance",GuidanceOptions.class);if(o==null||!o.enabled())return null;
        Path cal=Paths.get(job.getString("calibrationFile"));String sha=FilesIO.digest(Files.readAllBytes(cal));
        JSONObject calibration=FilesIO.object(cal);Quality.Normalization n=job.getObject("normalization",Quality.Normalization.class);
        if(!sha.equals(job.getString("calibrationSha256"))||!Objects.equals(calibration.getString("inputHash"),job.getString("inputHash"))||
           !SearchRunner.METRICS.equals(calibration.getString("metricVersion"))||!SearchRunner.VERSION.equals(calibration.getString("algorithmVersion"))||
           calibration.getJSONArray("calibrationSeeds").toJavaList(Long.class).contains(config.seed)||!hash(n).equals(hash(calibration.get("normalization"))))throw new IllegalArgumentException("Guidance calibration binding mismatch");
        JSONObject context=new JSONObject();for(String key:Arrays.asList("inputHash","implementationHash","calibrationSha256"))context.put(key,job.get(key));
        JSONObject probability=job.getJSONObject("onlineControl");if(probability!=null){probability=JSON.parseObject(probability.toJSONString());probability.remove("mode");probability.remove("monitor");probability.remove("replayFile");}context.put("probabilityPolicy",probability);
        ExperimentConfig.JevOptions jev=job.getObject("jev",ExperimentConfig.JevOptions.class);context.put("jev",jev);
        GuidanceEvidence model=new GuidanceEvidence(job.getString("inputHash"));
        return new GuidanceSession(o,n,config,Paths.get(job.getString("guidanceDirectory")),context,model,
            t->GuidanceClients.generate(o,t),e->GuidanceClients.choose(jev,e));
    }
    public GuidanceSession(GuidanceOptions o,Quality.Normalization n,RunConfig cfg,Path directory,JSONObject context,GuidanceEvidence model,Generator generator,Chooser chooser)throws Exception{
        o.validate();OnlineControlSession.validateNormalization(n);if(cfg.evaluationBudget!=0)throw new IllegalArgumentException("Guidance requires nominal generations");
        options=JSON.parseObject(JSON.toJSONString(o),GuidanceOptions.class);normalization=JSON.parseObject(JSON.toJSONString(n),Quality.Normalization.class);
        this.populationSize=cfg.populationSize;this.directory=directory.toAbsolutePath();this.model=model;this.generator=generator;this.chooser=chooser;
        JSONObject policy=JSON.parseObject(JSON.toJSONString(o));policy.remove("mode");policy.remove("replayFile");
        JSONObject binding=new JSONObject();binding.put("context",context);binding.put("config",cfg);binding.put("policy",policy);binding.put("normalization",normalization);binding.put("dependencyHash",hash(model.snapshot));binding.put("version",VERSION);binding.put("promptVersion",GuidanceEvidence.PROMPT_VERSION);binding.put("choicePromptVersion",LegacyAblationProtocol.choiceVersion());
        contextHash=hash(binding);
        if(Files.exists(this.directory.resolve("guidance-manifest.json")))throw new IllegalArgumentException("Existing guidance run; choose a fresh output directory");
        Files.createDirectories(this.directory);model.save(this.directory);
        JSONObject manifest=new JSONObject();manifest.put("contextHash",contextHash);manifest.put("binding",binding);manifest.put("mode",o.mode);FilesIO.write(this.directory.resolve("guidance-manifest.json"),manifest);
        if("replay".equals(o.mode))for(String line:Files.readAllLines(Paths.get(o.replayFile),StandardCharsets.UTF_8))if(!line.trim().isEmpty())replay.add(JSON.parseObject(line));
    }
    private static JSONObject stats(){JSONObject s=new JSONObject();s.put("requestCount",0);s.put("failureCount",0);s.put("localBudgetRejectedCount",0);s.put("knownTokens",0L);s.put("usageComplete",true);s.put("responseMillis",0d);return s;}
    private static void tally(JSONObject s,JSONObject call){if(call.getBooleanValue("attempted")){s.put("requestCount",s.getIntValue("requestCount")+1);if(call.get("totalTokens")==null)s.put("usageComplete",false);else s.put("knownTokens",s.getLongValue("knownTokens")+call.getLongValue("totalTokens"));}if(!"success".equals(call.getString("status"))){String error=call.getString("error");String counter=error!=null&&error.startsWith("LOCAL_REQUEST_BUDGET_EXCEEDED")?"localBudgetRejectedCount":"failureCount";s.put(counter,s.getIntValue(counter)+1);}s.put("responseMillis",s.getDoubleValue("responseMillis")+call.getDoubleValue("responseMillis"));}
    private static JSONObject failure(Exception e){JSONObject a=new JSONObject();a.put("status","failed");a.put("attempted",false);a.put("error",e.getClass().getSimpleName());return a;}
    public static String gate(SolutionRecord candidate,SolutionRecord base,SolutionRecord reference,int metric,Quality.Normalization n,double tolerance){
        for(double v:candidate.objectives)if(!Double.isFinite(v))return "NON_FINITE";
        if(candidate.objectives[metric]<=base.objectives[metric])return "TARGET_NOT_STRICTLY_IMPROVED_AT_STORED_PRECISION";
        double[] loss=n.normalize(candidate.objectives),anchor=n.normalize(reference.objectives);
        for(int j=0;j<5;j++)if(j!=metric && loss[j]-anchor[j]>tolerance+1e-12)return "OTHER_METRIC_TOLERANCE_"+GuidanceEvidence.METRICS.get(j);return null;
    }
    public List<int[]> afterSelection(int generation,Population parent,Evaluator evaluator)throws Exception{
        if(generation%options.intervalGenerations!=0)return Collections.emptyList();
        long start=System.nanoTime();JSONObject w=new JSONObject();w.put("generation",generation);w.put("effectiveGeneration",generation+1);w.put("contextHash",contextHash);w.put("mode",options.mode);
        List<SolutionRecord> population=new ArrayList<>();Map<String,SolutionRecord> unique=new TreeMap<>();
        for(Chromosome c:parent.getPopulace())if(SearchRunner.valid(SearchRunner.genes(c))&&GuidanceEvidence.groupsValid(SearchRunner.genes(c))){SolutionRecord r=SearchRunner.record(c);unique.putIfAbsent(Arrays.toString(r.chromosome),r);}population.addAll(unique.values());
        List<SolutionRecord> front=Quality.front(population);w.put("front",front);int metric=GuidanceEvidence.weakness(front,normalization,w);
        SolutionRecord base=Quality.representative(front,normalization);if(reference==null&&base!=null)reference=base;w.put("base",base);w.put("fixedReference",reference);w.put("promptVersion",GuidanceEvidence.PROMPT_VERSION);
        String evidenceHash=hash(w);w.put("evidenceHash",evidenceHash);
        JSONObject old=null;if("replay".equals(options.mode)){
            if(replayIndex>=replay.size())throw new IllegalStateException("Missing guidance replay window");old=replay.get(replayIndex++);
            // mode is transport metadata; compare the original state with that mode for replay.
            JSONObject state=JSON.parseObject(JSON.toJSONString(w,JSONWriter.Feature.WriteNulls));state.remove("evidenceHash");state.put("mode",old.getString("mode"));
            if(!contextHash.equals(old.getString("contextHash"))||!hash(state).equals(old.getString("evidenceHash")))throw new IllegalStateException("Guidance replay state mismatch");
            w.put("sourceMode",old.getString("mode"));
        }
        JSONArray generated=new JSONArray(),evaluated=new JSONArray(),calls=new JSONArray(),selected=new JSONArray();w.put("generationCalls",generated);w.put("candidates",evaluated);w.put("selectionCalls",calls);w.put("selected",selected);
        List<SolutionRecord> legal=new ArrayList<>();Map<String,JSONObject> evidenceById=new LinkedHashMap<>();List<int[]> out=new ArrayList<>();
        if(metric<0||base==null){w.put("skipReason","NO_RANKABLE_METRIC_OR_VALID_FRONT");}
        else {
            JSONObject diagnostic=model.diagnostics(base);List<JSONObject> tasks=model.tasks(metric,base,options,diagnostic);w.put("diagnostic",diagnostic);w.put("tasks",tasks);
            if(old!=null&&!hash(tasks).equals(hash(old.get("tasks"))))throw new IllegalStateException("Replay prompt/diagnostic mismatch");
            boolean generationFailed=false;Set<String> seen=new HashSet<>();seen.add(Arrays.toString(base.chromosome));
            for(int t=0;t<tasks.size();t++) {
                JSONObject a;if(old!=null){if(t>=old.getJSONArray("generationCalls").size())throw new IllegalStateException("Replay missing generator response");a=old.getJSONArray("generationCalls").getJSONObject(t);}
                else {try{a=generator.generate(tasks.get(t));}catch(Exception e){a=failure(e);}if(a==null)a=failure(new IllegalStateException());tally(llmStats,a);}
                JSONObject call=JSON.parseObject(a.toJSONString());call.put("taskId",tasks.get(t).get("taskId"));call.put("replayed",old!=null);generated.add(call);
                if(!"success".equals(a.getString("status"))||a.getJSONArray("candidates")==null||a.getJSONArray("candidates").isEmpty()||a.getJSONArray("candidates").size()>options.candidatesPerTask){generationFailed=true;w.put("skipReason","GENERATION_FAILED");break;}
                Set<String> proposalIds=new HashSet<>();
                for(Object raw:a.getJSONArray("candidates")){
                    JSONObject c=new JSONObject();String id="candidate-"+(evaluated.size()+1);c.put("id",id);c.put("taskId",tasks.get(t).get("taskId"));c.put("proposal",raw);evaluated.add(c);
                    try{
                        if(!(raw instanceof JSONObject))throw new IllegalArgumentException("Candidate must be object");JSONObject proposal=(JSONObject)raw;
                        if(!proposalIds.add(proposal.getString("id")))throw new IllegalArgumentException("Repeated candidate ID in task");
                        int[] proposed=model.compile(proposal,base),genes=SearchRunner.canonical(proposed);c.put("chromosome",genes);c.put("candidateHash",hash(genes));
                        if(!seen.add(Arrays.toString(genes)))throw new IllegalArgumentException("Duplicate/base partition");
                        SolutionRecord row=evaluator.evaluate(genes);candidateEvaluations++;c.put("solution",row);c.put("normalizedLoss",normalization.normalize(row.objectives));
                        List<JSONObject> changes=new ArrayList<>();for(int i=0;i<genes.length;i++)if(proposed[i]!=base.chromosome[i]){JSONObject change=new JSONObject();change.put("element",i<GA.DiagramParser.numofUC?"uc:"+i:"entity:"+(i-GA.DiagramParser.numofUC));change.put("before",base.chromosome[i]);change.put("after",proposed[i]);changes.add(change);}c.put("computedChanges",changes);
                        double[] delta=new double[5];for(int j=0;j<5;j++)delta[j]=row.metrics[j]-base.metrics[j];c.put("rawMetricDelta",delta);
                        String rejection=gate(row,base,reference,metric,normalization,options.lossTolerance);if(rejection!=null)throw new IllegalArgumentException(rejection);
                        JSONObject consistency=model.consistency(row);c.put("consistency",consistency);if(consistency.getBooleanValue("deterministicError"))throw new IllegalArgumentException("DETERMINISTIC_CONSISTENCY_ERROR");
                        c.put("status","eligible");legal.add(row);evidenceById.put(id,c);
                    }catch(RuntimeException e){c.put("status","rejected");c.put("rejection",e.getMessage());}
                }
            }
            if(!generationFailed&&!legal.isEmpty()){
                boolean same=true;String first=null;for(JSONObject c:evidenceById.values()){
                    JSONObject consistency=c.getJSONObject("consistency");String signature=consistency.getBooleanValue("deterministicError")+":"+consistency.getJSONArray("useCaseOperation").size()+":"+consistency.getJSONArray("operationEntity").size();if(first==null)first=signature;else if(!first.equals(signature))same=false;
                }
                w.put("consistencyNonDiscriminative",same);w.put("consistencyNote",same?"All eligible candidates retain original associations and derived access support. Structural checks do not distinguish correctness; JEV may still consider allocation evidence and actual metric costs.":"See individual evidence; no invented behavior.");
                LinkedHashMap<String,JSONObject> remaining=new LinkedHashMap<>(evidenceById);
                for(int k=0;k<Math.min(options.maxSelected,populationSize)&&!remaining.isEmpty();k++){
                    JSONObject e=new JSONObject();e.put("contextHash",contextHash);e.put("windowEvidenceHash",evidenceHash);e.put("targetMetric",GuidanceEvidence.METRICS.get(metric));e.put("metricDefinitions",SelectionEvidence.metricDefinitions());e.put("selectionPromptVersion",LegacyAblationProtocol.choiceVersion());e.put("metricOrder",GuidanceEvidence.METRICS);e.put("base",base);e.put("reference",reference);e.put("normalization",normalization);e.put("dependencySnapshotHash",hash(model.snapshot));e.put("consistencyNonDiscriminative",same);
                    JSONObject names=new JSONObject();names.put("useCases",model.snapshot.get("useCases"));names.put("entities",model.snapshot.get("entities"));if(!LegacyAblationProtocol.enabled())e.put("elementNames",names);
                    JSONObject choices=new JSONObject();choices.putAll(remaining);choices.put("KEEP",Collections.singletonMap("meaning","Stop selecting further candidates"));e.put("choices",choices);e.put("alreadySelected",JSON.parseArray(selected.toJSONString()));
                    JSONObject a;
                    if(old!=null){if(k>=old.getJSONArray("selectionCalls").size())throw new IllegalStateException("Replay missing Choice");JSONObject prior=old.getJSONArray("selectionCalls").getJSONObject(k);e.put("windowEvidenceHash",old.getString("evidenceHash"));if(!hash(e).equals(prior.getString("evidenceHash")))throw new IllegalStateException("Replay Choice evidence mismatch");a=prior.getJSONObject("response");}
                    else if("numeric".equals(options.mode)){a=new JSONObject();a.put("status","success");a.put("attempted",false);a.put("choice",numeric(remaining,selected,metric));a.put("source","numeric target-improvement then partition diversity; no JEV");}
                    else {try{a=chooser.choose(e);}catch(Exception failure){a=failure(failure);}if(a==null)a=failure(new IllegalStateException());tally(jevStats,a);}
                    JSONObject call=new JSONObject();call.put("evidence",JSON.parseObject(e.toJSONString()));call.put("evidenceHash",hash(e));call.put("response",a);call.put("replayed",old!=null);calls.add(call);
                    String choice=a.getString("choice");if(!"success".equals(a.getString("status"))||!choices.containsKey(choice)){w.put("selectionStopped","CHOICE_FAILED");break;}if("KEEP".equals(choice)){w.put("selectionStopped","KEEP");break;}
                    JSONObject accepted=remaining.remove(choice);selected.add(JSON.parseObject(accepted.toJSONString()));out.add(accepted.getJSONObject("solution").getObject("chromosome",int[].class));
                }
            }else if(!generationFailed)w.put("skipReason",tasks.isEmpty()?"NO_SUPPORTED_TASK":"NO_ELIGIBLE_IMPROVEMENT");
        }
        w.put("selectedCount",out.size());w.put("windowMillis",(System.nanoTime()-start)/1e6);millis+=(System.nanoTime()-start)/1e6;
        if(old!=null&&!hash(evaluated).equals(hash(old.get("candidates"))))throw new IllegalStateException("Replay evaluated candidate hash mismatch");
        if(old!=null){JSONArray prior=JSON.parseArray(old.getJSONArray("selected").toJSONString());for(Object raw:prior)((JSONObject)raw).remove("retainedAfterNextSelection");if(!hash(selected).equals(hash(prior)))throw new IllegalStateException("Replay selected batch mismatch");}
        windows.add(w);publish(w);return out;
    }
    private String numeric(Map<String,JSONObject> remaining,JSONArray selected,int metric){
        String best=null;double score=Double.NEGATIVE_INFINITY,diversity=-1;
        for(Map.Entry<String,JSONObject> entry:remaining.entrySet()){
            SolutionRecord row=entry.getValue().getObject("solution",SolutionRecord.class);double value=row.objectives[metric];double distance=selected.isEmpty()?0:Double.POSITIVE_INFINITY;
            for(Object raw:selected){int[] previous=((JSONObject)raw).getJSONObject("solution").getObject("chromosome",int[].class);int d=0;for(int i=0;i<previous.length;i++)if(previous[i]!=row.chromosome[i])d++;distance=Math.min(distance,d);}
            if(best==null||value>score||value==score&&distance>diversity){best=entry.getKey();score=value;diversity=distance;}
        }return best;
    }
    private void publish(JSONObject w)throws Exception{FilesIO.write(directory.resolve(String.format(Locale.ROOT,"window-%04d.json",w.getIntValue("generation"))),w);}
    /** Retention is observed only after the next normal environment selection. */
    public void recordRetention(int completedGeneration,Population parent)throws Exception{
        if(windows.isEmpty())return;JSONObject w=windows.get(windows.size()-1);if(w.getIntValue("effectiveGeneration")!=completedGeneration)return;
        Set<String> present=new HashSet<>();for(Chromosome c:parent.getPopulace())present.add(Arrays.toString(SearchRunner.canonical(SearchRunner.genes(c))));int retained=0;
        for(Object raw:w.getJSONArray("selected")){JSONObject c=(JSONObject)raw;boolean has=present.contains(Arrays.toString(c.getJSONObject("solution").getObject("chromosome",int[].class)));c.put("retainedAfterNextSelection",has);if(has)retained++;}w.put("retainedCount",retained);publish(w);
    }
    public Map<String,Object> finish()throws Exception{
        if("replay".equals(options.mode)&&replayIndex!=replay.size())throw new IllegalStateException("Unused guidance replay windows");
        JSONObject summary=new JSONObject();summary.put("version",VERSION);summary.put("mode",options.mode);summary.put("contextHash",contextHash);summary.put("windowCount",windows.size());summary.put("candidateEvaluations",candidateEvaluations);summary.put("guidanceMillis",millis);summary.put("auditDirectory",directory.toString());
        for(JSONObject s:Arrays.asList(llmStats,jevStats))s.put("totalTokens",s.getBooleanValue("usageComplete")?s.get("knownTokens"):null);summary.put("llm",llmStats);summary.put("jev",jevStats);
        int count=0,retained=0;StringBuilder md=new StringBuilder("# 小模型生成与 JEV 批量优选\n\n模式：`"+options.mode+"`。仅提出下一代候选；NSGA-II 决定留存。\n\n|窗口结束代|短板|生成请求|候选|优选|下一代环境选择留存|说明|\n|---:|---|---:|---:|---:|---:|---|\n");
        StringBuilder jsonl=new StringBuilder();for(JSONObject w:windows){count+=w.getIntValue("selectedCount");retained+=w.getIntValue("retainedCount");md.append("|").append(w.get("generation")).append("|").append(w.get("selectedMetric")).append("|").append(w.getJSONArray("generationCalls").size()).append("|").append(w.getJSONArray("candidates").size()).append("|").append(w.getIntValue("selectedCount")).append("|").append(w.getIntValue("retainedCount")).append("|").append(w.getOrDefault("skipReason",w.getOrDefault("selectionStopped","完成"))).append("|\n");jsonl.append(w.toJSONString()).append('\n');}
        summary.put("selectedCount",count);summary.put("retainedCount",retained);summary.put("retentionRate",count==0?null:(double)retained/count);
        md.append("\n实际额外候选评价：").append(candidateEvaluations).append("。LLM 与 JEV 分别统计；未知 tokens 保持 null。\n\n四层 `.dep` 记录原关联及派生访问支持，不包含契约、读写类型或执行顺序。跨服务不自动等于不一致；一致性无区分度时如实标注。关闭生成不改变原随机数路径。\n\n报告中的优选不代表全局最优或保证指标改善。窗口信号受概率控制及引导共同影响。逐窗口 JSON 包含提示词、原始响应、拒绝原因、指标、证据、Choice 与留存。\n");
        Files.write(directory.resolve("guidance-windows.jsonl"),jsonl.toString().getBytes(StandardCharsets.UTF_8));Files.write(directory.resolve("guidance-analysis.md"),md.toString().getBytes(StandardCharsets.UTF_8));FilesIO.write(directory.resolve("guidance-summary.json"),summary);
        JSONObject hashes=new JSONObject();try(Stream<Path> paths=Files.list(directory)){for(Path p:(Iterable<Path>)paths.sorted()::iterator)if(Files.isRegularFile(p)&&!p.getFileName().toString().equals("guidance-files.json"))hashes.put(p.getFileName().toString(),FilesIO.digest(Files.readAllBytes(p)));}
        FilesIO.write(directory.resolve("guidance-files.json"),hashes);summary.put("auditIndexSha256",FilesIO.digest(Files.readAllBytes(directory.resolve("guidance-files.json"))));return summary;
    }
    public static boolean auditComplete(JSONObject summary){try{Path root=Paths.get(summary.getString("auditDirectory"));Path index=root.resolve("guidance-files.json");if(!FilesIO.digest(Files.readAllBytes(index)).equals(summary.getString("auditIndexSha256")))return false;JSONObject files=FilesIO.object(index);for(String name:files.keySet()){Path p=root.resolve(name).normalize();if(!p.startsWith(root)||!FilesIO.digest(Files.readAllBytes(p)).equals(files.getString(name)))return false;}return files.containsKey("guidance-summary.json")&&files.containsKey("current-model.dep");}catch(Exception e){return false;}}
}

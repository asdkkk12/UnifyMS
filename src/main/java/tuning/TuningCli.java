package tuning;

import com.alibaba.fastjson2.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Commands: all|calibrate|select|run|validate|import-results CONFIG [--system ID] [--selector mock|jev|numeric] [--seed N] [--file JSON]. */
public final class TuningCli {
    private final ExperimentConfig cfg;
    private final Path root;
    private final String binding;
    private final String codeHash;
    private final Map<String,String> flags;
    private final List<JSONObject> events=new ArrayList<>();
    private TuningCli(Path config,Map<String,String> flags)throws Exception {
        cfg=FilesIO.read(config,ExperimentConfig.class);cfg.validate();this.flags=flags;
        if(flags.containsKey("--control") && !Arrays.asList("off","rule","jev","replay").contains(flags.get("--control")))throw new IllegalArgumentException("Invalid --control");
        if(flags.containsKey("--monitor") && !Arrays.asList("true","false").contains(flags.get("--monitor")))throw new IllegalArgumentException("Invalid --monitor");
        Path base=config.toAbsolutePath().getParent();
        for(ExperimentConfig.SystemInput s:cfg.systems)s.input=base.resolve(s.input).normalize().toString();
        if(cfg.guidance.replayFile!=null)cfg.guidance.replayFile=base.resolve(cfg.guidance.replayFile).normalize().toString();
        if(cfg.onlineControl.replayFile!=null)cfg.onlineControl.replayFile=base.resolve(cfg.onlineControl.replayFile).normalize().toString();
        root=base.resolve(cfg.outputDirectory).normalize();
        codeHash=FilesIO.inputHash(Paths.get("src/main/java").toAbsolutePath());
        JSONObject identity=JSON.parseObject(JSON.toJSONString(cfg));identity.put("implementationHash",codeHash);
        binding=FilesIO.hash(identity.toJSONString());
        Path manifest=root.resolve("manifest.json");
        if(Files.exists(manifest) && !binding.equals(FilesIO.object(manifest).getString("binding")))
            throw new IllegalArgumentException("Output belongs to another experiment/code version; choose another outputDirectory");
        identity.put("binding",binding);identity.put("algorithmVersion",SearchRunner.VERSION);if(!Files.exists(manifest))FilesIO.write(manifest,identity);
    }
    public static void main(String[] args)throws Exception {
        if(args.length<2){System.out.println("Usage: tuning.TuningCli all|calibrate|select|run|validate|import-results CONFIG [--system ID] [--selector mock|jev|numeric] [--seed N] [--file JSON]");return;}
        Map<String,String> flags=new LinkedHashMap<>();
        for(int i=2;i<args.length;i+=2){if(i+1>=args.length || !Arrays.asList("--system","--selector","--seed","--file","--control","--replay-file","--monitor","--guidance","--guidance-replay-file").contains(args[i]))throw new IllegalArgumentException("Invalid flag");flags.put(args[i],args[i+1]);}
        if(!Arrays.asList("all","calibrate","select","run","validate","import-results","compare-online","compare-guidance").contains(args[0]))throw new IllegalArgumentException("Unknown command");
        TuningCli cli=new TuningCli(Paths.get(args[1]),flags);cli.execute(args[0]);
    }
    private void execute(String command)throws Exception {
        long start=System.nanoTime();JSONArray systems=new JSONArray();boolean failed=false;boolean matched=false;
        for(ExperimentConfig.SystemInput system:cfg.systems) {
            if(flags.containsKey("--system") && !system.id.equals(flags.get("--system")))continue;
            matched=true;JSONObject status=new JSONObject();systems.add(status);status.put("system",system.id);
            long systemStart=System.nanoTime();
            try {
                Path dir=root.resolve(system.id);Files.createDirectories(dir);String hash=FilesIO.inputHash(Paths.get(system.input));
                Path snapshot=dir.resolve("input.json");
                if(Files.exists(snapshot) && !hash.equals(FilesIO.object(snapshot).getString("inputHash")))throw new IllegalStateException("Input changed; use a new output directory");
                JSONObject input=new JSONObject();input.put("input",system.input);input.put("inputHash",hash);if(!Files.exists(snapshot))FilesIO.write(snapshot,input);
                System.out.println(system.id+": "+command+" ("+cfg.profile+")");
                if(command.equals("all") || command.equals("calibrate"))timed("calibrate",system.id,()->calibrate(system,hash,dir));
                if(command.equals("all") || command.equals("select"))timed("select",system.id,()->select(dir));
                if(command.equals("all") || command.equals("validate"))timed("validate",system.id,()->validate(system,hash,dir));
                if(command.equals("all") || command.equals("run"))timed("run",system.id,()->runSelected(system,hash,dir));
                if(command.equals("compare-guidance"))timed("compare-guidance",system.id,()->compareGuidance(system,hash,dir));
                if(command.equals("compare-online"))timed("compare-online",system.id,()->compareOnline(system,hash,dir));
                if(command.equals("import-results"))timed("import-results",system.id,()->importResults(system,hash,dir));
                status.put("status","success");
            }catch(Exception e){failed=true;status.put("status","failed");status.put("error",e.toString());System.err.println(system.id+": "+e.getMessage());}
            status.put("elapsedMillis",(System.nanoTime()-systemStart)/1e6);
        }
        if(!matched)throw new IllegalArgumentException("Unknown --system");
        if(command.equals("all") || command.equals("validate"))aggregate();
        JSONObject report=new JSONObject();report.put("command",command);report.put("profile",cfg.profile);report.put("binding",binding);
        report.put("systems",systems);report.put("events",events);report.put("endToEndMillis",(System.nanoTime()-start)/1e6);
        Path execution=root.resolve("executions").resolve(System.currentTimeMillis()+"-"+UUID.randomUUID()+".json");FilesIO.write(execution,report);
        if(failed)throw new IllegalStateException("Some systems failed; see "+execution);
    }
    private interface Action {void run()throws Exception;}
    private void timed(String stage,String system,Action action)throws Exception {
        long t=System.nanoTime();JSONObject e=new JSONObject();events.add(e);e.put("stage",stage);e.put("system",system);
        try{action.run();e.put("status","success");}catch(Exception ex){e.put("status","failed");throw ex;}
        finally{e.put("elapsedMillis",(System.nanoTime()-t)/1e6);}
    }
    private RunConfig copy(RunConfig config,long seed,long budget) {
        RunConfig c=JSON.parseObject(JSON.toJSONString(config),RunConfig.class);c.seed=seed;c.evaluationBudget=budget;c.validate();return c;
    }
    private RunResult trial(ExperimentConfig.SystemInput system,String inputHash,Path dir,RunConfig c)throws Exception {
        return trial(system,inputHash,dir,c,null,null);
    }
    private RunResult trial(ExperimentConfig.SystemInput system,String inputHash,Path dir,RunConfig c,OnlineControlOptions online,JSONObject calibration)throws Exception {
        return trial(system,inputHash,dir,c,online,calibration,null);
    }
    private RunResult trial(ExperimentConfig.SystemInput system,String inputHash,Path dir,RunConfig c,OnlineControlOptions online,JSONObject calibration,GuidanceOptions guide)throws Exception {
        boolean monitored=online!=null && online.enabled(),guided=guide!=null&&guide.enabled();
        JSONObject identity=JSON.parseObject(JSON.toJSONString(c));identity.remove("id");
        identity.put("inputHash",inputHash);identity.put("implementationHash",codeHash);identity.put("metrics",SearchRunner.METRICS);identity.put("algorithm",SearchRunner.VERSION);
        if(guided){guide.validate();identity.put("guidance",guide);identity.put("guidanceVersion",GuidanceSession.VERSION);identity.put("guidancePromptVersion",GuidanceEvidence.PROMPT_VERSION);identity.put("jev",cfg.jev);if("replay".equals(guide.mode))identity.put("guidanceReplaySha256",FilesIO.digest(Files.readAllBytes(Paths.get(guide.replayFile))));}
        if(monitored || guided) {
            if(monitored)online.validate();OnlineControlSession.validateNormalization(calibration.getObject("normalization",Quality.Normalization.class));
            identity.put("onlineControl",online);identity.put("normalizationHash",OnlineControlSession.hash(calibration.getJSONObject("normalization")));
            identity.put("calibrationSha256",FilesIO.digest(Files.readAllBytes(dir.resolve("calibration.json"))));
            identity.put("onlinePolicyVersion",OnlineControlSession.VERSION);identity.put("onlinePromptVersion",OnlineJevClient.PROMPT_VERSION);identity.put("jev",cfg.jev);
            if("replay".equals(online.mode))identity.put("replaySha256",FilesIO.digest(Files.readAllBytes(Paths.get(online.replayFile))));
        }
        String key=FilesIO.hash(identity.toJSONString());Path trial=runDirectory(dir,online,guide).resolve("trials").resolve(key);Files.createDirectories(trial);
        Path resultPath=trial.resolve("result.json");
        if(Files.exists(resultPath)) {
            JSONObject previous=FilesIO.object(resultPath);
            if("success".equals(previous.getString("status")) && key.equals(previous.getString("taskKey")) && (!monitored || OnlineControlSession.auditComplete(previous.getJSONObject("onlineControl"))) && (!guided || GuidanceSession.auditComplete(previous.getJSONObject("guidance")))) {
                JSONObject reuse=new JSONObject();reuse.put("stage","reuse");reuse.put("system",system.id);reuse.put("config",c.id);reuse.put("seed",c.seed);reuse.put("taskKey",key);events.add(reuse);
                // Fastjson2 2.0.24's JSONObject conversion fails on null Map values
                // (legitimate unknown token usage). Parse the original JSON directly.
                RunResult r=FilesIO.read(resultPath,RunResult.class);r.config=c;return r;
            }
        }
        JSONObject request=new JSONObject();request.put("input",system.input);request.put("inputHash",inputHash);request.put("config",c);request.put("taskKey",key);
        if(monitored || guided) {
            request.put("onlineControl",online);request.put("normalization",calibration.get("normalization"));request.put("jev",cfg.jev);
            request.put("calibrationFile",dir.resolve("calibration.json").toAbsolutePath().toString());
            request.put("calibrationSha256",identity.get("calibrationSha256"));request.put("implementationHash",codeHash);
            request.put("auditDirectory",trial.resolve("audit").toAbsolutePath().toString());
            if(Files.exists(trial.resolve("audit/control-manifest.json")))throw new IllegalStateException("Incomplete or modified online audit; use a new output directory: "+trial);
        }
        if(guided){request.put("guidance",guide);request.put("guidanceDirectory",trial.resolve("guidance").toAbsolutePath().toString());if(Files.exists(trial.resolve("guidance/guidance-manifest.json")))throw new IllegalStateException("Incomplete guidance audit; choose a new directory");}
        Path requestPath=trial.resolve("request.json");FilesIO.write(requestPath,request);
        String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        ProcessBuilder builder=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),Worker.class.getName(),requestPath.toString(),resultPath.toString());
        builder.redirectErrorStream(true);builder.redirectOutput(trial.resolve("worker.log").toFile());
        Process process=builder.start();boolean finished=process.waitFor(cfg.workerTimeoutSeconds,TimeUnit.SECONDS);
        if(!finished){process.destroyForcibly();process.waitFor();JSONObject f=new JSONObject();f.put("status","failed");f.put("taskKey",key);f.put("error","Worker timeout");FilesIO.write(resultPath,f);throw new IOException("Worker timeout: "+trial);}
        if(process.exitValue()!=0 || !Files.exists(resultPath)) {
            String reason=Files.exists(resultPath)?FilesIO.object(resultPath).getString("error"):"No result file";
            throw new IOException("Worker failed: "+reason+"; log: "+trial.resolve("worker.log"));
        }
        RunResult result=FilesIO.read(resultPath,RunResult.class);
        if(!"success".equals(result.status) || !key.equals(result.taskKey))throw new IOException("Invalid trial result: "+trial);
        if(!inputHash.equals(FilesIO.inputHash(Paths.get(system.input))))throw new IOException("Input changed");
        return result;
    }
    private void calibrate(ExperimentConfig.SystemInput system,String hash,Path dir)throws Exception {
        List<RunResult> runs=new ArrayList<>();long budget=cfg.evaluationBudget;
        // Every candidate completes its own nominal generation count; no C0-derived cap.
        for(Long seed:cfg.calibrationSeeds) {
            RunResult r=trial(system,hash,dir,copy(cfg.candidate("C0"),seed,budget));runs.add(r);
            
        }
        List<RunConfig> candidates=new ArrayList<>(cfg.candidates);candidates.sort(Comparator.comparing(c->c.id));
        for(RunConfig candidate:candidates)if(!candidate.id.equals("C0"))for(Long seed:cfg.calibrationSeeds)
            runs.add(trial(system,hash,dir,copy(candidate,seed,budget)));
        Quality.Normalization norm=Quality.fit(runs);JSONObject summary=new JSONObject();summary.put("binding",binding);summary.put("inputHash",hash);
        summary.put("profile",cfg.profile);summary.put("metricVersion",SearchRunner.METRICS);summary.put("algorithmVersion",SearchRunner.VERSION);
        summary.put("stoppingMode","nominal-generations");summary.put("configurationVersion",cfg.configurationVersion);summary.put("evaluationBudget",budget);summary.put("normalization",norm);summary.put("structure",runs.get(0).structure);
        summary.put("objectiveOrder",Arrays.asList("-IFN","CHD","CMQ","Instability","UCM"));summary.put("allObjectivesMaximized",true);
        summary.put("calibrationSeeds",cfg.calibrationSeeds);summary.put("validationSeeds",cfg.validationSeeds);
        JSONObject grouped=new JSONObject();summary.put("candidates",grouped);List<JSONObject> csv=new ArrayList<>();
        for(RunConfig candidate:candidates) {
            JSONObject entry=new JSONObject();entry.put("config",candidate);JSONArray trials=new JSONArray();double sum=0;
            for(RunResult r:runs)if(candidate.id.equals(r.config.id)) {
                JSONObject row=JSON.parseObject(JSON.toJSONString(Quality.evaluate(r,norm)));
                row.put("seed",r.config.seed);row.put("config",candidate.id);row.put("taskKey",r.taskKey);row.put("evaluations",r.evaluations);
                row.put("completedGenerations",r.completedGenerations);row.put("unusedBudget",r.unusedBudget);row.put("searchMillis",r.searchMillis);
                row.put("front",r.front);trials.add(row);csv.add(row);sum+=row.getDoubleValue("hv");
            }
            entry.put("trials",trials);entry.put("meanHv",sum/trials.size());grouped.put(candidate.id,entry);
        }
        FilesIO.write(dir.resolve("calibration.json"),summary);writeCsv(dir.resolve("calibration.csv"),csv);
    }
    private JSONObject calibration(Path dir)throws Exception {
        JSONObject summary=FilesIO.object(dir.resolve("calibration.json"));
        if(!binding.equals(summary.getString("binding")) || !summary.getString("inputHash").equals(FilesIO.object(dir.resolve("input.json")).getString("inputHash")))
            throw new IllegalArgumentException("Calibration version/input mismatch");return summary;
    }
    private String backend() {String value=flags.getOrDefault("--selector",cfg.selector);if(!Arrays.asList("mock","jev","numeric").contains(value))throw new IllegalArgumentException("Unknown selector");return value;}
    private void select(Path dir)throws Exception {
        JSONObject evidence=calibration(dir);choose(dir,evidence,"numeric");if(!backend().equals("numeric"))choose(dir,evidence,backend());
    }
    private JSONObject choose(Path dir,JSONObject evidence,String selector)throws Exception {
        String evidenceHash=FilesIO.hash(evidence.toJSONString());Path file=dir.resolve("selection-"+selector+".json");
        if(Files.exists(file)){JSONObject r=FilesIO.object(file);if(SelectionEvidence.currentRecord(r,selector) && "success".equals(r.getString("status")) && evidenceHash.equals(r.getString("evidenceHash")) && binding.equals(r.getString("binding"))) {
            JSONObject reuse=new JSONObject();reuse.put("stage","reuse-selection");reuse.put("selector",selector);reuse.put("file",file.toString());events.add(reuse);return r;
        }}
        ConfigurationSelector adapter=selector.equals("numeric")?new Selectors.NumericSelector():selector.equals("mock")?new Selectors.MockJevSelector():new Selectors.JevSelector(cfg.jev,System.getenv("TYPESAFE_API_KEY"));
        JSONObject result=adapter.select(evidence,file);cfg.candidate(result.getString("choice"));
        result.put("evidenceHash",evidenceHash);result.put("binding",binding);result.put("inputHash",evidence.getString("inputHash"));
        result.put("config",cfg.candidate(result.getString("choice")));result.put("evaluationBudget",evidence.getLongValue("evaluationBudget"));
        FilesIO.write(file,result);return result;
    }
    private JSONObject selected(Path dir,String selector)throws Exception {
        JSONObject r=FilesIO.object(dir.resolve("selection-"+selector+".json"));JSONObject evidence=calibration(dir);
        if(!SelectionEvidence.currentRecord(r,selector) || !"success".equals(r.getString("status")) || !binding.equals(r.getString("binding")) || !FilesIO.hash(evidence.toJSONString()).equals(r.getString("evidenceHash")))
            throw new IllegalArgumentException("Selection failed or obsolete: "+selector);return r;
    }
    private void runSelected(ExperimentConfig.SystemInput system,String hash,Path dir)throws Exception {
        JSONObject summary=calibration(dir),choice=selected(dir,backend());long seed=flags.containsKey("--seed")?Long.parseLong(flags.get("--seed")):cfg.validationSeeds.get(0);
        if(cfg.calibrationSeeds.contains(seed))throw new IllegalArgumentException("Formal run seed overlaps calibration");
        OnlineControlOptions online=onlineOptions();GuidanceOptions guide=guidanceOptions();
        RunResult r=trial(system,hash,dir,copy(cfg.candidate(choice.getString("choice")),seed,summary.getLongValue("evaluationBudget")),online,summary,guide);
        JSONObject out=JSON.parseObject(JSON.toJSONString(Quality.evaluate(r,summary.getObject("normalization",Quality.Normalization.class))));
        out.put("run",r);out.put("selector",backend());out.put("profile",cfg.profile);out.put("controlMode",online.mode);
        out.put("guidanceMode",guide.mode);
        Path runDir=runDirectory(dir,online,guide);Files.createDirectories(runDir);
        FilesIO.write(runDir.resolve("run-"+backend()+"-"+seed+".json"),out);
    }
    private static Path runDirectory(Path dir,OnlineControlOptions online,GuidanceOptions guide){
        Path base=online!=null&&online.enabled()?dir.resolve("online-"+online.mode):dir;
        return guide!=null&&guide.enabled()?base.resolve("guidance-"+guide.mode):base;
    }
    private GuidanceOptions guidanceOptions(){
        GuidanceOptions o=JSON.parseObject(JSON.toJSONString(cfg.guidance),GuidanceOptions.class);
        if(flags.containsKey("--guidance"))o.mode=flags.get("--guidance");
        if(flags.containsKey("--guidance-replay-file"))o.replayFile=Paths.get(flags.get("--guidance-replay-file")).toAbsolutePath().toString();o.validate();return o;
    }
    private void compareGuidance(ExperimentConfig.SystemInput system,String hash,Path dir)throws Exception{
        JSONObject cal=calibration(dir),selection=selected(dir,backend());JSONArray rows=new JSONArray();
        for(long seed:cfg.validationSeeds)for(String mode:Arrays.asList("off","numeric","jev")){
            OnlineControlOptions online=onlineOptions();if(!"jev".equals(online.mode))throw new IllegalArgumentException("compare-guidance requires --control jev for all arms");
            GuidanceOptions guide=guidanceOptions();guide.mode=mode;
            RunResult r=trial(system,hash,dir,copy(cfg.candidate(selection.getString("choice")),seed,0),online,cal,guide);
            JSONObject row=JSON.parseObject(JSON.toJSONString(Quality.evaluate(r,cal.getObject("normalization",Quality.Normalization.class))));row.put("run",r);row.put("seed",seed);row.put("arm",mode);row.put("selector",backend());row.put("profile",cfg.profile);row.put("controlMode",online.mode);row.put("guidanceMode",mode);rows.add(row);
            Path runDir=runDirectory(dir,online,guide);FilesIO.write(runDir.resolve("run-"+backend()+"-"+seed+".json"),row);
        }
        JSONObject out=new JSONObject();out.put("rows",rows);out.put("stoppingMode","nominal-generations");out.put("note","Paired seeds and initial configuration. Independent state-bound generation batches; evaluation and API costs differ. Not an equal-budget comparison.");
        JSONObject stats=new JSONObject();for(String mode:Arrays.asList("off","numeric","jev")){
            List<Double> hv=new ArrayList<>(),evaluations=new ArrayList<>(),cost=new ArrayList<>();List<List<Double>> metrics=new ArrayList<>();for(int i=0;i<5;i++)metrics.add(new ArrayList<>());
            for(Object raw:rows){JSONObject row=(JSONObject)raw;if(!mode.equals(row.getString("arm")))continue;hv.add(row.getDoubleValue("hv"));evaluations.add(row.getJSONObject("run").getDoubleValue("evaluations"));cost.add(row.getJSONObject("run").getDoubleValue("guidanceMillis"));for(int i=0;i<5;i++)metrics.get(i).add(row.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(i));}
            JSONObject stat=new JSONObject();stat.put("hv",Statistics.describe(hv));stat.put("evaluations",Statistics.describe(evaluations));stat.put("guidanceMillis",Statistics.describe(cost));JSONArray ms=new JSONArray();for(List<Double> m:metrics)ms.add(Statistics.describe(m));stat.put("representativeMetrics",ms);stats.put(mode,stat);
        }out.put("perArm",stats);FilesIO.write(dir.resolve("guidance-comparison.json"),out);
    }
    private OnlineControlOptions onlineOptions() {
        OnlineControlOptions o=JSON.parseObject(JSON.toJSONString(cfg.onlineControl),OnlineControlOptions.class);
        if(flags.containsKey("--control"))o.mode=flags.get("--control");
        if(flags.containsKey("--monitor"))o.monitor=Boolean.parseBoolean(flags.get("--monitor"));
        if(flags.containsKey("--replay-file"))o.replayFile=Paths.get(flags.get("--replay-file")).toAbsolutePath().toString();
        o.validate();return o;
    }
    private void compareOnline(ExperimentConfig.SystemInput system,String hash,Path dir)throws Exception {
        JSONObject cal=calibration(dir),selection=selected(dir,backend());JSONArray rows=new JSONArray();
        Map<String,List<Double>> hv=new LinkedHashMap<>();Map<String,List<List<Double>>> metrics=new LinkedHashMap<>();
        for(String arm:Arrays.asList("rule","jev")){hv.put(arm,new ArrayList<>());List<List<Double>> d=new ArrayList<>();for(int j=0;j<5;j++)d.add(new ArrayList<>());metrics.put(arm,d);}
        List<Double> jevVsRule=new ArrayList<>();List<List<Double>> jevRuleMetrics=new ArrayList<>();for(int j=0;j<5;j++)jevRuleMetrics.add(new ArrayList<>());
        for(long seed:cfg.validationSeeds) {
            JSONObject base=null,ruleRow=null;
            for(String mode:Arrays.asList("off","rule","jev")) {
                OnlineControlOptions o=JSON.parseObject(JSON.toJSONString(cfg.onlineControl),OnlineControlOptions.class);o.mode=mode;o.monitor=true;o.validate();
                RunResult r=trial(system,hash,dir,copy(cfg.candidate(selection.getString("choice")),seed,cal.getLongValue("evaluationBudget")),o,cal);
                JSONObject row=JSON.parseObject(JSON.toJSONString(Quality.evaluate(r,cal.getObject("normalization",Quality.Normalization.class))));
                row.put("run",r);row.put("arm",mode);row.put("seed",seed);row.put("selector",backend());row.put("profile",cfg.profile);row.put("controlMode",mode);
                Path armDir=dir.resolve("online-"+mode);Files.createDirectories(armDir);FilesIO.write(armDir.resolve("run-"+backend()+"-"+seed+".json"),row);
                JSONObject compact=JSON.parseObject(row.toJSONString());compact.remove("run");compact.put("searchMillis",r.searchMillis);compact.put("monitoringMillis",r.monitoringMillis);compact.put("controlMillis",r.controlMillis);compact.put("workerMillis",r.workerMillis);compact.put("validRate",r.validRate);compact.put("onlineControl",r.onlineControl);rows.add(compact);
                if("rule".equals(mode))ruleRow=row;
                if("jev".equals(mode)) {
                    jevVsRule.add(row.getDoubleValue("hv")-ruleRow.getDoubleValue("hv"));
                    for(int j=0;j<5;j++)jevRuleMetrics.get(j).add(row.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j)-ruleRow.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j));
                }
                if(base==null)base=row;else {
                    hv.get(mode).add(row.getDoubleValue("hv")-base.getDoubleValue("hv"));
                    for(int j=0;j<5;j++)metrics.get(mode).get(j).add(row.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j)-base.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j));
                }
            }
        }
        JSONObject out=new JSONObject(),paired=new JSONObject();out.put("rows",rows);out.put("binding",binding);out.put("initialChoice",selection.get("choice"));out.put("metricOrder",Arrays.asList("IFN","CHD","CMQ","Instability","UCM"));
        for(String arm:hv.keySet()){JSONObject stat=Statistics.paired(hv.get(arm));JSONArray diffs=new JSONArray();for(List<Double> d:metrics.get(arm))diffs.add(Statistics.paired(d));stat.put("metricDifferences",diffs);paired.put(arm,stat);}
        JSONObject againstRule=Statistics.paired(jevVsRule);JSONArray againstRuleMetrics=new JSONArray();for(List<Double> d:jevRuleMetrics)againstRuleMetrics.add(Statistics.paired(d));againstRule.put("metricDifferences",againstRuleMetrics);out.put("jevVsRule",againstRule);
        JSONObject perArm=new JSONObject();
        for(String arm:Arrays.asList("off","rule","jev")) {
            JSONObject statistics=new JSONObject();
            for(String field:Arrays.asList("hv","searchMillis","monitoringMillis","controlMillis","workerMillis","validRate")) {
                List<Double> values=new ArrayList<>();for(Object value:rows){JSONObject r=(JSONObject)value;if(arm.equals(r.getString("arm")))values.add(r.getDoubleValue(field));}
                statistics.put(field,Statistics.describe(values));
            }
            JSONArray metricStats=new JSONArray();for(int j=0;j<5;j++){List<Double> values=new ArrayList<>();for(Object value:rows){JSONObject r=(JSONObject)value;if(arm.equals(r.getString("arm")))values.add(r.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j));}metricStats.add(Statistics.describe(values));}
            statistics.put("representativeMetrics",metricStats);perArm.put(arm,statistics);
        }
        out.put("perArm",perArm);out.put("vsMonitoredStatic",paired);out.put("note","Same initial choice, seeds, budget and per-generation monitoring. History information differs between rule and JEV; not a proof of JEV superiority over every history-aware controller.");
        FilesIO.write(dir.resolve("online-comparison.json"),out);
    }
    private void validate(ExperimentConfig.SystemInput system,String hash,Path dir)throws Exception {
        JSONObject summary=calibration(dir);Quality.Normalization norm=summary.getObject("normalization",Quality.Normalization.class);
        Map<String,String> arms=new LinkedHashMap<>();arms.put("default","C0");arms.put("numeric",selected(dir,"numeric").getString("choice"));
        arms.put(backend(),selected(dir,backend()).getString("choice"));
        List<JSONObject> rows=new ArrayList<>();Map<String,List<Double>> hvDeltas=new LinkedHashMap<>();
        Map<String,List<List<Double>>> metricDeltas=new LinkedHashMap<>();
        for(String arm:arms.keySet()){hvDeltas.put(arm,new ArrayList<>());List<List<Double>> m=new ArrayList<>();for(int j=0;j<5;j++)m.add(new ArrayList<>());metricDeltas.put(arm,m);}
        for(Long seed:cfg.validationSeeds) {
            Map<String,JSONObject> paired=new LinkedHashMap<>();
            for(Map.Entry<String,String> arm:arms.entrySet()) {
                RunResult r=trial(system,hash,dir,copy(cfg.candidate(arm.getValue()),seed,summary.getLongValue("evaluationBudget")));
                JSONObject row=JSON.parseObject(JSON.toJSONString(Quality.evaluate(r,norm)));
                row.put("arm",arm.getKey());row.put("config",arm.getValue());row.put("seed",seed);row.put("taskKey",r.taskKey);
                row.put("evaluations",r.evaluations);row.put("searchMillis",r.searchMillis);row.put("inputMillis",r.inputMillis);
                row.put("evaluationExportMillis",r.evaluationExportMillis);row.put("workerMillis",r.workerMillis);
                row.put("front",r.front);rows.add(row);paired.put(arm.getKey(),row);
            }
            JSONObject base=paired.get("default");
            for(String arm:arms.keySet()) {
                JSONObject row=paired.get(arm);hvDeltas.get(arm).add(row.getDoubleValue("hv")-base.getDoubleValue("hv"));
                if(row.getJSONObject("representative")!=null && base.getJSONObject("representative")!=null)
                    for(int j=0;j<5;j++)metricDeltas.get(arm).get(j).add(row.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j)-base.getJSONObject("representative").getJSONArray("metrics").getDoubleValue(j));
            }
        }
        JSONObject out=new JSONObject();out.put("binding",binding);out.put("profile",cfg.profile);out.put("mock",backend().equals("mock"));out.put("arms",arms);out.put("rows",rows);
        JSONObject comparisons=new JSONObject();for(String arm:arms.keySet()) {
            JSONObject p=Statistics.paired(hvDeltas.get(arm));JSONArray metrics=new JSONArray();
            for(List<Double> deltas:metricDeltas.get(arm))metrics.add(Statistics.paired(deltas));p.put("metricDifferences",metrics);comparisons.put(arm,p);
        }
        out.put("vsDefault",comparisons);out.put("metricOrder",Arrays.asList("IFN","CHD","CMQ","Instability","UCM"));
        JSONObject costs=new JSONObject();for(String arm:arms.keySet())if(!arm.equals("default")) {
            JSONObject s=selected(dir,arm);JSONObject cost=new JSONObject();cost.put("totalTokens",s.get("totalTokens"));cost.put("totalResponseMillis",s.get("totalResponseMillis"));costs.put(arm,cost);}
        out.put("selectionCosts",costs);FilesIO.write(dir.resolve("validation-"+backend()+".json"),out);writeCsv(dir.resolve("validation-"+backend()+".csv"),rows);
    }
    private void aggregate()throws IOException {
        JSONObject out=new JSONObject(),systems=new JSONObject();JSONArray missing=new JSONArray();
        Map<String,List<Double>> differences=new LinkedHashMap<>();
        for(ExperimentConfig.SystemInput system:cfg.systems) {
            Path file=root.resolve(system.id).resolve("validation-"+backend()+".json");
            if(!Files.exists(file)){missing.add(system.id);continue;}
            JSONObject result=FilesIO.object(file);
            if(!binding.equals(result.getString("binding"))){missing.add(system.id);continue;}
            JSONObject comparisons=result.getJSONObject("vsDefault");systems.put(system.id,comparisons);
            for(String arm:comparisons.keySet()) {
                Double mean=comparisons.getJSONObject(arm).getDouble("meanDifference");
                if(mean!=null)differences.computeIfAbsent(arm,k->new ArrayList<>()).add(mean);
            }
        }
        JSONObject overall=new JSONObject();for(Map.Entry<String,List<Double>> e:differences.entrySet())overall.put(e.getKey(),Statistics.paired(e.getValue()));
        out.put("binding",binding);out.put("profile",cfg.profile);out.put("mock",backend().equals("mock"));out.put("missingSystems",missing);
        out.put("perSystem",systems);out.put("overallHvDifference",overall);
        out.put("aggregation","Equal weight per system; bootstrap unit is the system mean paired HV difference, not individual seeds");
        FilesIO.write(root.resolve("summary-"+backend()+".json"),out);
    }
    private static void writeCsv(Path file,List<JSONObject> rows)throws IOException {
        StringBuilder csv=new StringBuilder("arm,config,seed,taskKey,hv,validRate,validUniqueCount,nonDominatedCount,evaluations,searchMillis,IFN,CHD,CMQ,Instability,UCM,serviceCount\n");
        for(JSONObject row:rows){for(String key:Arrays.asList("arm","config","seed","taskKey","hv","validRate","validUniqueCount","nonDominatedCount","evaluations","searchMillis"))
                csv.append(row.get(key)==null?"":row.get(key)).append(',');
            JSONObject r=row.getJSONObject("representative");if(r!=null){JSONArray values=r.getJSONArray("metrics");for(int j=0;j<5;j++)csv.append(values.get(j)).append(',');csv.append(r.get("serviceCount"));}else csv.append(",,,,,");csv.append('\n');}
        Files.write(file,csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void importResults(ExperimentConfig.SystemInput system,String hash,Path dir)throws Exception {
        if(!flags.containsKey("--file"))throw new IllegalArgumentException("--file required");
        ExternalResults.importFile(Paths.get(flags.get("--file")),dir,calibration(dir),system,hash);
    }
}

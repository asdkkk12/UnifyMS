import tuning.*;
import com.alibaba.fastjson2.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Repeat selected-pair or A/B/C ablation with immutable prior offline evidence. */
public final class RepeatOnlineComparison {
    /** Reconstruct the original tree hash, allowing only a documented prompt-client change. */
    private static void verifyPromptOnlyMigration(Path root,String oldHash,String newHash,JSONObject policy,Path policyBase)throws Exception {
        JSONObject proof=policy==null?null:policy.getJSONObject("sourceCompatibility");
        if(proof==null || !"tuning/OnlineJevClient.java".equals(proof.getString("relativePath")) || !oldHash.equals(proof.getString("oldImplementationHash")) || !newHash.equals(proof.getString("newImplementationHash")))
            throw new IllegalArgumentException("Code changed; a verified prompt-only migration record is required");
        Path original=policyBase.resolve(proof.getString("originalFile")).normalize();
        byte[] oldBytes=Files.readAllBytes(original);
        if(!FilesIO.digest(oldBytes).equals(proof.getString("originalSha256")) || !FilesIO.digest(Files.readAllBytes(root.resolve("tuning/OnlineJevClient.java"))).equals(proof.getString("replacementSha256")))
            throw new IllegalArgumentException("Prompt migration file hashes mismatch");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(Path file:FilesIO.javaFiles(root)) {
            String name=root.relativize(file).toString().replace('\\','/');
            bytes.write(name.getBytes(StandardCharsets.UTF_8));bytes.write(0);
            bytes.write("tuning/OnlineJevClient.java".equals(name)?oldBytes:Files.readAllBytes(file));bytes.write(0);
        }
        if(!oldHash.equals(FilesIO.digest(bytes.toByteArray())))throw new IllegalArgumentException("Changes extend beyond the documented prompt client; frozen offline evidence cannot be reused");
    }
    public static void main(String[] args) throws Exception {
        if(args.length<4 || args.length>6)throw new IllegalArgumentException("SOURCE_CONFIG OUTPUT CASE SEEDS [selected-pair|abc] [POLICY_CONFIG]");
        boolean abc=args.length>=5 && "abc".equals(args[4]);
        if(args.length>=5 && !abc && !"selected-pair".equals(args[4]))throw new IllegalArgumentException("Unknown comparison design");
        Path policyPath=args.length==6?Paths.get(args[5]).toAbsolutePath():null;
        JSONObject policy=policyPath==null?null:FilesIO.object(policyPath);
        List<String> arms=abc?Arrays.asList("A","B","C"):Arrays.asList("off","jev");
        Path source=Paths.get(args[0]).toAbsolutePath(),out=Paths.get(args[1]).toAbsolutePath();
        String caseId=args[2];
        ExperimentConfig cfg=FilesIO.read(source,ExperimentConfig.class);cfg.validate();
        if(!"formal".equals(cfg.profile) || !"jev".equals(cfg.selector))throw new IllegalArgumentException("A formal real-JEV source experiment is required");
        Path base=source.getParent();
        for(ExperimentConfig.SystemInput s:cfg.systems)s.input=base.resolve(s.input).normalize().toString();
        if(cfg.onlineControl.replayFile!=null)cfg.onlineControl.replayFile=base.resolve(cfg.onlineControl.replayFile).normalize().toString();
        ExperimentConfig.SystemInput system=cfg.systems.stream().filter(s->s.id.equals(caseId)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown case"));
        Path sourceRoot=base.resolve(cfg.outputDirectory).normalize(),sourceCase=sourceRoot.resolve(caseId);
        String implementationHash=FilesIO.inputHash(Paths.get("src/main/java").toAbsolutePath());
        JSONObject oldManifest=FilesIO.object(sourceRoot.resolve("manifest.json"));
        String sourceImplementationHash=oldManifest.getString("implementationHash");
        JSONObject identity=JSON.parseObject(JSON.toJSONString(cfg));identity.put("implementationHash",sourceImplementationHash);
        String binding=FilesIO.hash(identity.toJSONString());
        if(!binding.equals(oldManifest.getString("binding")))throw new IllegalArgumentException("Source config differs from frozen experiment");
        if(!implementationHash.equals(sourceImplementationHash))verifyPromptOnlyMigration(Paths.get("src/main/java").toAbsolutePath(),sourceImplementationHash,implementationHash,policy,policyPath==null?null:policyPath.getParent());
        OnlineControlOptions effectiveControl=JSON.parseObject(JSON.toJSONString(cfg.onlineControl),OnlineControlOptions.class);
        if(policy!=null) {
            JSONObject overrides=policy.getJSONObject("onlineControl");
            if(overrides==null || !overrides.keySet().equals(Collections.singleton("gainThreshold")) || !OnlineJevClient.PROMPT_VERSION.equals(policy.getString("promptVersion")))throw new IllegalArgumentException("Policy override must set only gainThreshold and match the compiled prompt version");
            effectiveControl.gainThreshold=overrides.getDoubleValue("gainThreshold");effectiveControl.validate();
        }
        JSONObject cal=FilesIO.object(sourceCase.resolve("calibration.json")),selection=FilesIO.object(sourceCase.resolve("selection-jev.json"));
        String inputHash=FilesIO.inputHash(Paths.get(system.input));
        if(!binding.equals(cal.getString("binding")) || !binding.equals(selection.getString("binding")) || !inputHash.equals(cal.getString("inputHash")) || !inputHash.equals(selection.getString("inputHash")))
            throw new IllegalArgumentException("Source input/calibration/selection binding mismatch");
        if(!SelectionEvidence.currentRecord(selection,"jev") || !"success".equals(selection.getString("status")) || selection.getBooleanValue("mock") || !FilesIO.hash(cal.toJSONString()).equals(selection.getString("evidenceHash")))
            throw new IllegalArgumentException("Source selection is not a valid real JEV decision");
        if(!SearchRunner.METRICS.equals(cal.getString("metricVersion")) || !SearchRunner.VERSION.equals(cal.getString("algorithmVersion")))
            throw new IllegalArgumentException("Source algorithm/metric version mismatch");
        Quality.Normalization norm=cal.getObject("normalization",Quality.Normalization.class);OnlineControlSession.validateNormalization(norm);
        RunConfig initial=cfg.candidate(selection.getString("choice"));
        if(!OnlineControlSession.hash(initial).equals(OnlineControlSession.hash(selection.getObject("config",RunConfig.class))))throw new IllegalArgumentException("Selected parameters differ from source configuration");
        LinkedHashSet<Long> seedSet=new LinkedHashSet<>();
        for(String value:args[3].split(",")){long seed=Long.parseLong(value);if(!seedSet.add(seed) || cfg.calibrationSeeds.contains(seed))throw new IllegalArgumentException("Duplicate or calibration seed");}
        if(seedSet.isEmpty())throw new IllegalArgumentException("No test seeds");
        if(System.getenv("TYPESAFE_API_KEY")==null || System.getenv("TYPESAFE_API_KEY").trim().isEmpty())throw new IllegalArgumentException("TYPESAFE_API_KEY must be provided at runtime");
        if(Files.exists(out.resolve("repeat-manifest.json")))throw new IllegalArgumentException("Choose a fresh output directory");
        Path caseDir=out.resolve("experiment").resolve(caseId);Files.createDirectories(caseDir);
        // Preserve source bytes and original bindings; describe the new run in a separate manifest.
        for(String file:Arrays.asList("calibration.json","selection-jev.json","input.json"))Files.copy(sourceCase.resolve(file),caseDir.resolve(file));
        Files.copy(source,out.resolve("source-run-config.json"));
        String calibrationSha=FilesIO.digest(Files.readAllBytes(caseDir.resolve("calibration.json")));
        JSONObject manifest=new JSONObject();
        manifest.put("repeatRunId",UUID.randomUUID().toString());manifest.put("sourceConfig",source.toString());manifest.put("sourceManifest",sourceRoot.resolve("manifest.json").toString());
        manifest.put("sourceBinding",binding);manifest.put("sourceManifestSha256",FilesIO.digest(Files.readAllBytes(sourceRoot.resolve("manifest.json"))));
        manifest.put("sourceSelection",sourceCase.resolve("selection-jev.json").toString());manifest.put("sourceSelectionSha256",FilesIO.digest(Files.readAllBytes(caseDir.resolve("selection-jev.json"))));
        manifest.put("calibrationSha256",calibrationSha);manifest.put("implementationHash",implementationHash);manifest.put("inputHash",inputHash);
        manifest.put("sourceImplementationHash",sourceImplementationHash);manifest.put("promptVersion",OnlineJevClient.PROMPT_VERSION);manifest.put("effectiveOnlineControl",effectiveControl);
        if(policy!=null){manifest.put("policyId",policy.get("policyId"));manifest.put("policyConfigFile",policyPath.toString());manifest.put("policyConfigSha256",FilesIO.digest(Files.readAllBytes(policyPath)));manifest.put("sourceCompatibility",policy.get("sourceCompatibility"));Files.copy(policyPath,out.resolve("effective-policy.json"));}
        manifest.put("case",caseId);manifest.put("input",system.input);manifest.put("initialChoice",initial.id);manifest.put("initialConfig",initial);manifest.put("evaluationBudget",cal.getLongValue("evaluationBudget"));
        manifest.put("seeds",seedSet);manifest.put("arms",arms);manifest.put("design",abc?"abc-ablation":"selected-pair");manifest.put("offlineJevRequestsThisRun",0);manifest.put("reuseFormalResults",false);
        if(abc){JSONObject groups=new JSONObject();for(String arm:arms){JSONObject group=new JSONObject();group.put("config",("A".equals(arm)?cfg.candidate("C0"):initial));group.put("controlMode","C".equals(arm)?"jev":"off");groups.put(arm,group);}manifest.put("groups",groups);}
        manifest.put("startedAt",Instant.now().toString());manifest.put("status","running");FilesIO.write(out.resolve("repeat-manifest.json"),manifest);
        JSONArray rows=new JSONArray();
        try {
            MermSnapshot.main(new String[]{system.input,out.resolve("model-snapshot.json").toString()});
            for(long seed:seedSet)for(String arm:arms) {
                String mode=abc?("C".equals(arm)?"jev":"off"):arm;
                RunConfig startConfig=abc && "A".equals(arm)?cfg.candidate("C0"):initial;
                RunConfig run=JSON.parseObject(JSON.toJSONString(startConfig),RunConfig.class);run.seed=seed;run.evaluationBudget=cal.getLongValue("evaluationBudget");run.validate();
                OnlineControlOptions control=JSON.parseObject(JSON.toJSONString(effectiveControl),OnlineControlOptions.class);control.mode=mode;control.monitor=true;control.replayFile=null;control.validate();
                JSONObject job=new JSONObject();job.put("input",system.input);job.put("inputHash",inputHash);job.put("config",run);job.put("onlineControl",control);job.put("normalization",cal.get("normalization"));job.put("jev",cfg.jev);
                job.put("calibrationFile",caseDir.resolve("calibration.json").toString());job.put("calibrationSha256",calibrationSha);job.put("implementationHash",implementationHash);job.put("repeatRunId",manifest.get("repeatRunId"));job.put("experimentalArm",arm);
                String taskKey=OnlineControlSession.hash(job);job.put("taskKey",taskKey);
                Path armDirectory=caseDir.resolve(abc?"group-"+arm:"online-"+mode);
                Path trial=armDirectory.resolve("trials").resolve(taskKey);Files.createDirectories(trial);
                job.put("auditDirectory",trial.resolve("audit").toString());FilesIO.write(trial.resolve("request.json"),job);
                System.out.println("START "+arm+" config="+run.id+" control="+mode+" seed="+seed+" budget="+run.evaluationBudget);System.out.flush();
                String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
                Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"tuning.Worker",trial.resolve("request.json").toString(),trial.resolve("result.json").toString()).redirectErrorStream(true).redirectOutput(trial.resolve("worker.log").toFile()).start();
                if(!child.waitFor(cfg.workerTimeoutSeconds,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();throw new IllegalStateException("Worker timeout: "+trial);}
                if(child.exitValue()!=0)throw new IllegalStateException("Worker failed; inspect "+trial.resolve("worker.log"));
                RunResult result=FilesIO.read(trial.resolve("result.json"),RunResult.class);
                if(!"success".equals(result.status) || !taskKey.equals(result.taskKey) || !inputHash.equals(result.inputHash) || !OnlineControlSession.auditComplete(JSON.parseObject(JSON.toJSONString(result.onlineControl))))throw new IllegalStateException("Invalid result/audit");
                JSONObject row=JSON.parseObject(JSON.toJSONString(Quality.evaluate(result,norm)));row.put("run",result);row.put("arm",arm);row.put("seed",seed);row.put("selector",abc && "A".equals(arm)?"preset":"jev");row.put("profile","formal");row.put("controlMode",mode);
                Path runPath=armDirectory.resolve("run-"+(abc?arm:"jev")+"-"+seed+".json");FilesIO.write(runPath,row);
                JSONObject compact=JSON.parseObject(row.toJSONString());compact.remove("run");compact.put("runFile",runPath.toString());compact.put("onlineControl",result.onlineControl);
                for(String key:Arrays.asList("evaluations","completedGenerations","searchMillis","monitoringMillis","searchComputeMillis","controlMillis","workerMillis"))compact.put(key,JSON.parseObject(JSON.toJSONString(result)).get(key));
                rows.add(compact);manifest.put("completedRuns",rows.size());FilesIO.write(out.resolve("repeat-manifest.json"),manifest);
                System.out.println("DONE "+arm+" seed="+seed+" HV="+row.getDoubleValue("hv"));System.out.flush();
            }
            if(!implementationHash.equals(FilesIO.inputHash(Paths.get("src/main/java").toAbsolutePath())) || !inputHash.equals(FilesIO.inputHash(Paths.get(system.input))))throw new IllegalStateException("Code/input changed during experiment");
            JSONObject comparison=new JSONObject();comparison.put("arms",arms);comparison.put("rows",rows);comparison.put("initialChoice",initial.id);comparison.put("metricOrder",Arrays.asList("IFN","CHD","CMQ","Instability","UCM"));
            comparison.put("repeatRunId",manifest.get("repeatRunId"));comparison.put("calibrationSha256",calibrationSha);comparison.put("note",abc?"A preset C0 fixed, B offline JEV choice fixed, C same choice with online JEV; paired seeds and full budget.":"Fresh off/jev searches, same frozen offline choice, paired seeds and full budget; no rule arm.");
            FilesIO.write(caseDir.resolve("online-comparison.json"),comparison);
            boolean degraded=rows.stream().anyMatch(v->((JSONObject)v).getJSONObject("onlineControl").getIntValue("fallbackCount")>0);
            manifest.put("status",degraded?"transport-degraded":"success");manifest.put("finishedAt",Instant.now().toString());
        } catch(Exception e) {manifest.put("status","failed");manifest.put("error",e.toString());throw e;}
        finally {FilesIO.write(out.resolve("repeat-manifest.json"),manifest);}
    }
}

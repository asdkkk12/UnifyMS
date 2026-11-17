import tuning.*;
import com.alibaba.fastjson2.*;
import java.nio.file.*;
import java.util.*;
public class CocomeV2Retry {
 public static void main(String[] args)throws Exception {
  String command=args[0];Path configPath=Paths.get(args[1]).toAbsolutePath();ExperimentConfig cfg=FilesIO.read(configPath,ExperimentConfig.class);
  Path root=configPath.getParent().resolve(cfg.outputDirectory).normalize(),dir=root.resolve("CoCoME");
  Path calPath=dir.resolve("calibration.json"),audit=dir.resolve("selection-jev.json");JSONObject cal=FilesIO.object(calPath);
  String implementation=FilesIO.inputHash(Paths.get("src/main/java").toAbsolutePath());String evidenceHash=FilesIO.hash(cal.toJSONString());
  if(command.equals("select")) {
   JSONObject chosen=new Selectors.JevSelector(cfg.jev,System.getenv("TYPESAFE_API_KEY")).select(cal,audit);
   chosen.put("sourceCalibrationBinding",cal.get("binding"));chosen.put("evidenceHash",evidenceHash);
   chosen.put("sourceCalibrationFile",calPath.toString());chosen.put("sourceCalibrationSha256",FilesIO.digest(Files.readAllBytes(calPath)));
   chosen.put("selectionImplementationHash",implementation);chosen.put("inputHash",cal.get("inputHash"));
   chosen.put("config",cfg.candidate(chosen.getString("choice")));chosen.put("evaluationBudget",cal.get("evaluationBudget"));
   chosen.put("recoveryNote","Fresh six-profile calibration retained with its original implementation binding. Only request transport compaction changed after context-limit rejection; search/objectives/normalization unchanged.");
   FilesIO.write(audit,chosen);System.out.println("Selected "+chosen.getString("choice"));return;
  }
  if(!command.equals("run"))throw new IllegalArgumentException("select or run required");
  JSONObject selected=FilesIO.object(audit);
  if(!"success".equals(selected.getString("status")) || !SelectionEvidence.VERSION.equals(selected.getString("selectionPolicyVersion")) || !evidenceHash.equals(selected.getString("evidenceHash")))throw new IllegalArgumentException("Invalid/new-policy selection required");
  RunConfig run=JSON.parseObject(JSON.toJSONString(cfg.candidate(selected.getString("choice"))),RunConfig.class);run.seed=101;run.evaluationBudget=cal.getLongValue("evaluationBudget");run.validate();
  JSONObject identity=JSON.parseObject(JSON.toJSONString(run));identity.remove("id");identity.put("inputHash",cal.get("inputHash"));identity.put("implementationHash",implementation);identity.put("metrics",SearchRunner.METRICS);identity.put("algorithm",SearchRunner.VERSION);
  String taskKey=FilesIO.hash(identity.toJSONString());Path trial=dir.resolve("trials").resolve(taskKey);if(Files.exists(trial.resolve("result.json")))throw new IllegalArgumentException("Fresh run result already exists");Files.createDirectories(trial);
  JSONObject request=new JSONObject();request.put("input",configPath.getParent().resolve(cfg.systems.get(0).input).normalize().toString());request.put("inputHash",cal.get("inputHash"));request.put("config",run);request.put("taskKey",taskKey);request.put("implementationHash",implementation);FilesIO.write(trial.resolve("request.json"),request);
  ProcessBuilder p=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Worker.class.getName(),trial.resolve("request.json").toString(),trial.resolve("result.json").toString());p.redirectErrorStream(true);p.redirectOutput(trial.resolve("worker.log").toFile());
  long started=System.nanoTime();if(p.start().waitFor()!=0)throw new IllegalStateException("Worker failed: "+trial);
  RunResult result=FilesIO.read(trial.resolve("result.json"),RunResult.class);
  JSONObject output=JSON.parseObject(JSON.toJSONString(Quality.evaluate(result,cal.getObject("normalization",Quality.Normalization.class))));
  output.put("run",result);output.put("selector","jev");output.put("profile",cfg.profile);output.put("selectionPolicyVersion",SelectionEvidence.VERSION);output.put("implementationHash",implementation);output.put("sourceCalibrationBinding",cal.get("binding"));output.put("sourceCalibrationSha256",FilesIO.digest(Files.readAllBytes(calPath)));output.put("stageMillis",(System.nanoTime()-started)/1e6);
  FilesIO.write(dir.resolve("run-jev-101.json"),output);System.out.println("Formal run complete: HV="+output.get("hv"));
 }
}

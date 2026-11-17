package tuning;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Evaluates external chromosomes with the same program, never accepts claimed quality scores. */
public final class ExternalResults {
    public static void importFile(Path file,Path dir,JSONObject calibration,ExperimentConfig.SystemInput system,String hash)throws Exception {
        JSONObject submission=FilesIO.object(file);String method=submission.getString("method");
        if(method==null || !method.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid method ID");
        if(!hash.equals(submission.getString("inputHash")) || !SearchRunner.METRICS.equals(submission.getString("metricVersion")))throw new IllegalArgumentException("External input/metric version mismatch");
        if(!Arrays.asList("tool","llm-direct").contains(submission.getString("kind")))throw new IllegalArgumentException("Expected tool or llm-direct kind");
        JSONArray runs=submission.getJSONArray("runs");if(runs==null || runs.isEmpty())throw new IllegalArgumentException("No external runs");
        Set<Long> seen=new HashSet<>();JSONArray evaluated=new JSONArray();
        Path root=dir.resolve("external").resolve(method).resolve(FilesIO.hash(submission.toJSONString()));Files.createDirectories(root);
        FilesIO.write(root.resolve("submission.json"),submission);
        for(int i=0;i<runs.size();i++) {
            JSONObject run=runs.getJSONObject(i);
            if(!run.containsKey("seed") || !calibration.getJSONArray("validationSeeds").toJavaList(Long.class).contains(run.getLong("seed")) || !seen.add(run.getLong("seed")))throw new IllegalArgumentException("External seeds must be unique validation seeds");
            if(run.getJSONArray("chromosomes")==null)throw new IllegalArgumentException("Missing chromosomes");
            JSONObject job=new JSONObject();job.put("input",system.input);job.put("inputHash",hash);job.put("chromosomes",run.getJSONArray("chromosomes"));
            job.put("taskKey",FilesIO.hash(job.toJSONString()));
            Path request=root.resolve(i+"-request.json"),result=root.resolve(i+"-result.json");FilesIO.write(request,job);
            ProcessBuilder b=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Worker.class.getName(),request.toString(),result.toString());
            b.redirectErrorStream(true);b.redirectOutput(root.resolve(i+"-worker.log").toFile());Process p=b.start();
            if(!p.waitFor(600,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor();throw new IOException("External evaluation timeout");}
            if(p.exitValue()!=0)throw new IOException("External evaluation failed: "+root);
            RunResult r=FilesIO.read(result,RunResult.class);
            JSONObject row=JSON.parseObject(JSON.toJSONString(Quality.evaluate(r,calibration.getObject("normalization",Quality.Normalization.class))));
            row.put("seed",run.getLong("seed"));row.put("invalidCount",r.invalidCount);row.put("front",r.front);
            row.put("reportedTotalTokens",run.get("totalTokens"));row.put("reportedTotalResponseMillis",run.get("totalResponseMillis"));
            row.put("reportedEndToEndMillis",run.get("endToEndMillis"));evaluated.add(row);
        }
        JSONObject out=new JSONObject();out.put("method",method);out.put("kind",submission.getString("kind"));out.put("inputHash",hash);
        out.put("binding",calibration.get("binding"));out.put("profile",calibration.get("profile"));out.put("rows",evaluated);out.put("costProvenance","externally reported; not measured by this program");
        FilesIO.write(root.resolve("evaluation.json"),out);
    }
}

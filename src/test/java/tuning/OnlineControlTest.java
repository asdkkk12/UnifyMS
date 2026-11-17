package tuning;

import GA.*;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.datastructure.Population;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OnlineControlTest {
    @Test void v2PromptUsesMeaningfulGainThresholdWithoutForcingAnAction() {
        JSONObject evidence=new JSONObject();evidence.put("gainThreshold",.0002);evidence.put("noveltyThreshold",.2);evidence.put("actions",new JSONObject());
        JSONObject request=OnlineJevClient.request(evidence,"fixture-model");
        String instructions=request.getJSONObject("questions").getJSONObject("configuration").getString("instructions");
        assertEquals(.0002,request.getJSONObject("state").getDoubleValue("gainThreshold"));
        assertTrue(instructions.contains("meanGain <= gainThreshold"));
        assertTrue(instructions.contains("A positive Gain alone is not sufficient reason to KEEP"));
        assertTrue(instructions.contains("NOT mandatory mappings"));
        assertFalse(instructions.contains("positive Gain suggests KEEP"));
        assertEquals("novelty-gain-history-choice-v2",OnlineJevClient.PROMPT_VERSION);
    }
    // Historical 30-generation fixtures explicitly retain their original policy.
    OnlineControlOptions legacyOptions(){OnlineControlOptions o=new OnlineControlOptions();o.intervalGenerations=30;return o;}
    @TempDir Path temp;
    @BeforeEach void load()throws Exception {ModelLoader.load(Paths.get("cocome.remodel"));}
    RunConfig config(int generations){RunConfig c=new RunConfig("C3",30,generations,.5,.25);c.seed=101;return c;}
    Quality.Normalization normalization(){Quality.Normalization n=new Quality.Normalization();n.min=new double[]{0,-2,-2,-2,-2};n.max=new double[]{30,2,2,2,2};return n;}
    OnlineControlSession session(OnlineControlOptions o,RunConfig c,String directory,OnlineControlSession.DecisionClient client)throws Exception {
        JSONObject context=new JSONObject();context.put("input","fixture");return new OnlineControlSession(o,normalization(),c,temp.resolve(directory),context,client);
    }
    List<JSONObject> lines(Path file)throws Exception {List<JSONObject> out=new ArrayList<>();for(String line:Files.readAllLines(file))if(!line.isEmpty())out.add(JSON.parseObject(line));return out;}
    RunResult.SolutionRecord point(int[] genes,double... objectives){RunResult.SolutionRecord r=new RunResult.SolutionRecord();r.chromosome=genes;r.objectives=objectives;return r;}

    @Test void noveltyCanonicalizesLabelsAndDeduplicates() {
        List<RunResult.SolutionRecord> p=Arrays.asList(point(new int[]{7,7,8},0,0,0,0,0));
        List<RunResult.SolutionRecord> q=Arrays.asList(point(new int[]{3,3,9},0,0,0,0,0),point(new int[]{0,1,1},0,0,0,0,0),point(new int[]{5,6,6},0,0,0,0,0));
        JSONObject s=OnlineControlSession.statistics(p,q,normalization());assertEquals(.5,s.getDoubleValue("novelty"));assertEquals(2,s.getIntValue("offspringUniqueCount"));assertEquals(0,s.getDoubleValue("gain"));
    }
    @Test void complementaryOffspringContributesDespiteLowerStandaloneHv() {
        Quality.Normalization n=new Quality.Normalization();Arrays.fill(n.max,1);Arrays.fill(n.reference,1);
        List<RunResult.SolutionRecord> p=Arrays.asList(point(new int[]{0,0},-.2,-.8,0,0,0));
        List<RunResult.SolutionRecord> q=Arrays.asList(point(new int[]{0,1},-.8,-.3,0,0,0));
        assertEquals(.16,Quality.hv(p,n),1e-12);assertEquals(.14,Quality.hv(q,n),1e-12);
        assertEquals(.10,OnlineControlSession.statistics(p,q,n).getDoubleValue("gain"),1e-12);
        assertFalse(OnlineControlSession.statistics(p,Collections.emptyList(),n).getBooleanValue("valid"));
        List<RunResult.SolutionRecord> dominated=Arrays.asList(point(new int[]{0,1},-.9,-.9,-.1,-.1,-.1));
        assertEquals(0,OnlineControlSession.statistics(p,dominated,n).getDoubleValue("gain"),1e-12);
    }
    @Test void monitoredKeepMatchesLegacyPopulationAndBudget()throws Exception {
        RunConfig c=config(65);RunResult legacy=new SearchRunner().run(c);
        OnlineControlOptions o=legacyOptions();o.monitor=true;
        RunResult monitored=new SearchRunner().run(c,session(o,c,"keep",null));
        assertEquals(JSON.toJSONString(legacy.population),JSON.toJSONString(monitored.population));
        assertEquals(JSON.toJSONString(legacy.front),JSON.toJSONString(monitored.front));assertEquals(legacy.evaluations,monitored.evaluations);
        List<JSONObject> decisions=lines(temp.resolve("keep/control-decisions.jsonl"));
        assertEquals(2,decisions.size());assertEquals(30,decisions.get(0).getIntValue("generation"));assertEquals(31,decisions.get(0).getIntValue("effectiveGeneration"));
        assertEquals(65,lines(temp.resolve("keep/generation-stats.jsonl")).size());
        assertTrue(OnlineControlSession.auditComplete((JSONObject)monitored.onlineControl));
        Files.write(temp.resolve("keep/generation-stats.jsonl"),"tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(OnlineControlSession.auditComplete((JSONObject)monitored.onlineControl));
    }
    @Test void realChoiceIsRespectedAndReplayMatchesEntirePopulation()throws Exception {
        RunConfig c=config(65);OnlineControlOptions o=legacyOptions();o.mode="jev";
        AtomicInteger count=new AtomicInteger();
        OnlineControlSession.DecisionClient fake=e->{
            int call=count.incrementAndGet();JSONArray h=e.getJSONArray("history");assertEquals(call,h.size());
            if(call==1){assertEquals(30,e.getIntValue("currentGeneration"));assertNull(h.getJSONObject(0).get("action"));}
            else {assertEquals("INCREASE",h.getJSONObject(0).getString("action"));assertEquals(.55,h.getJSONObject(0).getDoubleValue("probabilityAfter"),1e-12);}
            JSONObject a=new JSONObject();a.put("status","success");a.put("choice",call==1?"INCREASE":"DECREASE");return a;
        };
        RunResult actual=new SearchRunner().run(c,session(o,c,"controlled",fake));assertEquals(2,count.get());
        List<JSONObject> stats=lines(temp.resolve("controlled/generation-stats.jsonl"));assertEquals(.5,stats.get(29).getDoubleValue("probability"),1e-12);assertEquals(.55,stats.get(30).getDoubleValue("probability"),1e-12);
        assertEquals(.5,stats.get(60).getDoubleValue("probability"),1e-12);
        OnlineControlOptions replay=legacyOptions();replay.mode="replay";replay.replayFile=temp.resolve("controlled/control-decisions.jsonl").toString();
        RunResult repeated=new SearchRunner().run(c,session(replay,c,"replayed",null));
        assertEquals(JSON.toJSONString(actual.population),JSON.toJSONString(repeated.population));assertEquals(actual.evaluations,repeated.evaluations);
        assertNull(actual.onlineControl.get("totalTokens"));assertEquals(0,((Number)repeated.onlineControl.get("requestCount")).intValue());
        RunConfig wrong=config(65);wrong.seed=103;
        assertThrows(IllegalStateException.class,()->new SearchRunner().run(wrong,session(replay,wrong,"bad-replay",null)));
    }
    @Test void cappedBudgetAndTerminalGenerationNeverCall()throws Exception {
        OnlineControlOptions o=legacyOptions();o.mode="jev";
        AtomicInteger calls=new AtomicInteger();OnlineControlSession.DecisionClient fake=e->{calls.incrementAndGet();return new JSONObject();};
        for(int g:new int[]{1,29,30}){RunConfig c=config(g);new SearchRunner().run(c,session(o,c,"g"+g,fake));}
        assertEquals(0,calls.get());
        RunConfig c=config(100);c.evaluationBudget=30*(3*30+1);
        RunResult r=new SearchRunner().run(c,session(o,c,"budget",fake));assertEquals(30,r.completedGenerations);assertEquals(0,calls.get());
        assertEquals(c.evaluationBudget,r.evaluations);
    }
    @Test void failuresKeepAndHistoryIsBounded()throws Exception {
        OnlineControlOptions o=legacyOptions();o.mode="jev";o.intervalGenerations=2;o.historyWindows=3;
        AtomicInteger calls=new AtomicInteger();RunConfig c=config(11);
        RunResult result=new SearchRunner().run(c,session(o,c,"failure",e->{int i=calls.incrementAndGet();assertEquals(Math.min(i,3),e.getJSONArray("history").size());throw new IOException("offline");}));
        assertEquals(5,calls.get());assertEquals(.5,((Number)result.onlineControl.get("finalProbability")).doubleValue());assertEquals(5,((Number)result.onlineControl.get("fallbackCount")).intValue());
        for(JSONObject d:lines(temp.resolve("failure/control-decisions.jsonl")))assertEquals("KEEP",d.getString("action"));
    }
    @Test void fourQuadrantsAndBounds()throws Exception {
        OnlineControlOptions o=legacyOptions();JSONObject e=new JSONObject(),w=new JSONObject();e.put("currentWindow",w);w.put("valid",true);
        w.put("meanNovelty",.1);w.put("meanGain",0);assertEquals("INCREASE",OnlineControlSession.rule(e,o));
        w.put("meanNovelty",.2);assertEquals("DECREASE",OnlineControlSession.rule(e,o));
        w.put("meanGain",.0002);assertEquals("KEEP",OnlineControlSession.rule(e,o));w.put("meanNovelty",.1);assertEquals("KEEP",OnlineControlSession.rule(e,o));
        o.step=1;OnlineControlSession control=session(o,config(1),"bounds",null);assertEquals(.9,control.apply("INCREASE"));assertEquals(.1,control.apply("DECREASE"));
        RunConfig bad=config(1);bad.crossoverProbability=.95;assertThrows(IllegalArgumentException.class,()->session(o,bad,"outside",null));
        Quality.Normalization n=normalization();n.zeroSpan.set(0,true);assertThrows(IllegalArgumentException.class,()->OnlineControlSession.validateNormalization(n));
    }
    @Test void cachedRunPreservesUnknownUsage()throws Exception {
        RunResult r=new RunResult();r.onlineControl=new LinkedHashMap<>();r.onlineControl.put("mode","jev");r.onlineControl.put("totalTokens",null);
        Path file=temp.resolve("cached.json");FilesIO.write(file,r);
        RunResult restored=FilesIO.read(file,RunResult.class);assertTrue(restored.onlineControl.containsKey("totalTokens"));assertNull(restored.onlineControl.get("totalTokens"));
    }
    @Test void workerRejectsWrongCalibrationAndOverlappingSeed()throws Exception {
        JSONObject cal=new JSONObject();cal.put("inputHash","fixture");cal.put("metricVersion",SearchRunner.METRICS);cal.put("algorithmVersion",SearchRunner.VERSION);
        cal.put("calibrationSeeds",Arrays.asList(11));cal.put("normalization",JSON.parseObject(JSON.toJSONString(normalization())));
        Path file=temp.resolve("calibration.json");FilesIO.write(file,cal);
        JSONObject job=new JSONObject();OnlineControlOptions o=legacyOptions();o.mode="rule";job.put("onlineControl",o);job.put("inputHash","fixture");
        job.put("calibrationFile",file.toString());job.put("calibrationSha256",FilesIO.digest(Files.readAllBytes(file)));job.put("normalization",cal.get("normalization"));job.put("auditDirectory",temp.resolve("worker-audit").toString());
        RunConfig c=config(1);c.seed=11;assertThrows(IllegalArgumentException.class,()->OnlineControlSession.fromJob(job,c));
        c.seed=101;job.put("calibrationSha256","wrong");assertThrows(IllegalArgumentException.class,()->OnlineControlSession.fromJob(job,c));
        job.put("calibrationSha256",FilesIO.digest(Files.readAllBytes(file)));job.put("inputHash","other");assertThrows(IllegalArgumentException.class,()->OnlineControlSession.fromJob(job,c));
    }
    @Test void overallHttpDeadlineDoesNotRetry() {
        AtomicInteger calls=new AtomicInteger();ExperimentConfig.JevOptions j=new ExperimentConfig.JevOptions();j.endpoint="https://example.invalid";j.model="fixed-test";
        OnlineJevClient client=new OnlineJevClient(j,60,"test-secret") {
            @Override protected HttpURLConnection openConnection(URL url) {
                calls.incrementAndGet();return new HttpURLConnection(url) {
                    public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
                    @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
                    @Override public int getResponseCode()throws IOException {try{Thread.sleep(1000);}catch(InterruptedException e){throw new IOException("cancelled");}return 200;}
                };
            }
        };
        long start=System.nanoTime();JSONObject r=client.decide(new JSONObject());
        assertEquals("failed",r.getString("status"));assertEquals(1,calls.get());assertTrue((System.nanoTime()-start)/1e6<700);assertTrue(r.getString("error").contains("TimeoutException"));
    }
    private OnlineJevClient httpClient(String response,int status,AtomicInteger calls,boolean timeout) {
        ExperimentConfig.JevOptions j=new ExperimentConfig.JevOptions();j.endpoint="https://example.invalid/v1/systemone";j.model="jev-fixed-test";
        return new OnlineJevClient(j,100,"test-secret") {
            @Override protected HttpURLConnection openConnection(URL url) {
                calls.incrementAndGet();return new HttpURLConnection(url) {
                    public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
                    @Override public OutputStream getOutputStream()throws IOException {if(timeout)throw new SocketTimeoutException("simulated test-secret");return new ByteArrayOutputStream();}
                    @Override public int getResponseCode(){return status;}
                    @Override public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
                    @Override public InputStream getErrorStream(){return getInputStream();}
                };
            }
        };
    }
    @Test void httpChoiceValidationUsageAndNoRetryOrCredentialLeak() {
        AtomicInteger calls=new AtomicInteger();JSONObject e=new JSONObject();e.put("actions",new JSONObject());
        String valid="{\"model\":\"jev-fixed-test\",\"usage\":{\"input_tokens\":12,\"output_tokens\":3},\"answers\":{\"configuration\":{\"type\":\"choice\",\"choice\":\"DECREASE\"}}}";
        JSONObject r=httpClient(valid,200,calls,false).decide(e);assertEquals("success",r.getString("status"));assertEquals("DECREASE",r.getString("choice"));assertEquals(15,r.getLongValue("totalTokens"));assertEquals(1,calls.get());
        r=httpClient(valid.replace("DECREASE","UNKNOWN"),200,calls,false).decide(e);assertEquals("failed",r.getString("status"));assertNull(r.get("choice"));assertEquals(2,calls.get());
        r=httpClient("test-secret",401,calls,false).decide(e);assertEquals("failed",r.getString("status"));assertFalse(r.toJSONString().contains("test-secret"));assertNull(r.get("totalTokens"));assertEquals(3,calls.get());
        r=httpClient("",200,calls,true).decide(e);assertEquals("failed",r.getString("status"));assertFalse(r.toJSONString().contains("test-secret"));assertEquals(4,calls.get());
    }
}

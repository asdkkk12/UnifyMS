package tuning;
import GA.*;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.*;
import com.debacharya.nsgaii.datastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GuidanceTest {
    @TempDir Path temp;
    @BeforeEach void load()throws Exception{ModelLoader.load(Paths.get("cocome.remodel"));}
    Quality.Normalization norm(){Quality.Normalization n=new Quality.Normalization();n.min=new double[]{0,-1,-1,-1,-1};n.max=new double[]{20,0,1,0,1};return n;}
    RunResult.SolutionRecord evaluate(int[] genes){Chromosome c=SearchRunner.chromosome(genes);Service.calculateObjectiveValues(c,SearchRunner.objectives());return SearchRunner.record(c);}
    int[] baseGenes(){int[] g=new int[DiagramParser.numofUC+DiagramParser.numofEntity];for(int i=0;i<g.length;i++)g[i]=i%2;if(DiagramParser.instancegetRelatedEntity!=null)for(List<Integer> group:DiagramParser.instancegetRelatedEntity)for(int e:group)g[DiagramParser.numofUC+e]=g[DiagramParser.numofUC+group.get(0)];return g;}
    @Test void newTableAndDefaultsAreExact(){
        int[] ns={200,150,100,200,200,100},gs={100,150,200,100,100,200};double[] ps={.7,.7,.7,.5,.9,.9},cs={.5,.5,.5,.25,.75,.25};
        List<RunConfig> table=ExperimentConfig.defaults();for(int i=0;i<6;i++){RunConfig c=table.get(i);assertEquals(ns[i],c.populationSize);assertEquals(gs[i],c.generations);assertEquals(ps[i],c.crossoverProbability);assertEquals(cs[i],c.crossP);}
        assertEquals(10,new OnlineControlOptions().intervalGenerations);assertEquals(Arrays.asList(11L),new ExperimentConfig().calibrationSeeds);assertEquals(10,new ExperimentConfig().validationSeeds.size());
        ExperimentConfig e=new ExperimentConfig();ExperimentConfig.SystemInput input=new ExperimentConfig.SystemInput();input.id="CoCoME";input.input="cocome.remodel";e.systems.add(input);e.evaluationBudget=123;assertThrows(IllegalArgumentException.class,e::validate);
    }
    @Test void lossDirectionMedianZeroSpanAndTies(){
        Quality.Normalization n=norm();RunResult.SolutionRecord r=new RunResult.SolutionRecord();r.objectives=new double[]{-18,.1,.8,.8,.8};JSONObject d=new JSONObject();assertEquals(0,GuidanceEvidence.weakness(Arrays.asList(r),n,d));
        assertEquals(.9,n.normalize(r.objectives)[0],1e-12);assertEquals(.9,n.normalize(r.objectives)[1],1e-12);
        n.max[0]=n.min[0];n.zeroSpan.set(0,true);assertEquals(1,GuidanceEvidence.weakness(Arrays.asList(r),n,new JSONObject()));
        for(int j=0;j<5;j++){n.max[j]=n.min[j];n.zeroSpan.set(j,true);}assertEquals(-1,GuidanceEvidence.weakness(Arrays.asList(r),n,new JSONObject()));
    }
    @Test void completeMappingCompilerRejectsMissingUnknownAndGroups(){
        GuidanceEvidence m=new GuidanceEvidence("fixture");RunResult.SolutionRecord base=evaluate(baseGenes());JSONObject proposal=m.mapping(base);proposal.remove("serviceCount");proposal.put("id","p1");assertArrayEquals(base.chromosome,m.compile(proposal,base));
        proposal.getJSONObject("useCases").remove("uc:0");final JSONObject missing=proposal;assertThrows(IllegalArgumentException.class,()->m.compile(missing,base));
        proposal=m.mapping(base);proposal.remove("serviceCount");proposal.put("id","p2");proposal.getJSONObject("entities").put("unknown",0);final JSONObject unknown=proposal;assertThrows(IllegalArgumentException.class,()->m.compile(unknown,base));
        JSONObject noninteger=m.mapping(base);noninteger.remove("serviceCount");noninteger.put("id","p3");noninteger.getJSONObject("useCases").put("uc:0",0.0);assertThrows(IllegalArgumentException.class,()->m.compile(noninteger,base));
        JSONObject wrongBase=m.mapping(base);wrongBase.remove("serviceCount");wrongBase.put("id","p4");wrongBase.put("baseId","wrong");assertThrows(IllegalArgumentException.class,()->m.compile(wrongBase,base));
    }
    @Test void strictImprovementAndFrozenAnchorTolerance(){
        RunResult.SolutionRecord b=new RunResult.SolutionRecord(),r=new RunResult.SolutionRecord(),c=new RunResult.SolutionRecord();b.objectives=new double[]{-5,.5,.5,.5,.5};r.objectives=b.objectives.clone();c.objectives=b.objectives.clone();
        assertNotNull(GuidanceSession.gate(c,b,r,0,norm(),.02));c.objectives[0]=-4;assertNull(GuidanceSession.gate(c,b,r,0,norm(),.02));
        c.objectives[1]=.47;assertTrue(GuidanceSession.gate(c,b,r,0,norm(),.02).contains("CHD"));b.objectives[1]=.48;assertNotNull(GuidanceSession.gate(c,b,r,0,norm(),.02));
    }
    @Test void dependencySnapshotAndAllFiveTaskTemplatesAreReal()throws Exception{
        GuidanceEvidence m=new GuidanceEvidence("fixturehash");m.save(temp);String xml=new String(Files.readAllBytes(temp.resolve("current-model.dep")),StandardCharsets.UTF_8);assertTrue(xml.contains("useCaseOperations"));assertTrue(xml.contains("operationEntities"));
        RunResult.SolutionRecord b=evaluate(baseGenes());JSONObject d=m.diagnostics(b);GuidanceOptions o=new GuidanceOptions();o.mode="numeric";
        for(int j=0;j<5;j++){List<JSONObject> tasks=m.tasks(j,b,o,d);assertFalse(tasks.isEmpty());assertTrue(tasks.size()<=4);for(JSONObject t:tasks){assertTrue(t.containsKey("specificWitnesses"));assertTrue(t.getJSONObject("diagnostic").containsKey("frozenModel"));assertFalse(t.containsKey("testResults"));}}
        assertTrue(GuidanceEvidence.GENERATION_INSTRUCTIONS.contains("n^2"));assertTrue(GuidanceEvidence.GENERATION_INSTRUCTIONS.contains("Multiple moves"));assertTrue(GuidanceEvidence.GENERATION_INSTRUCTIONS.contains("not reference strength"));
        JSONObject consistency=m.consistency(b);assertFalse(consistency.getBooleanValue("deterministicError"));assertFalse(consistency.getJSONArray("operationEntity").isEmpty());assertTrue(consistency.toJSONString().contains("SUPPORTED_STRUCTURALLY"));
    }
    @Test void transportDoesNotRetryRedactsAndKeepsUnknownUsage()throws Exception{
        AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{requests.incrementAndGet();byte[] response="{\"model\":\"fixture\",\"echo\":\"fixture-secret\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try{JSONObject a=GuidanceClients.exchange("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-secret",2000,new JSONObject());assertEquals("success",a.getString("status"));assertNull(a.get("totalTokens"));assertFalse(a.toJSONString().contains("fixture-secret"));assertEquals(1,requests.get());}
        finally{server.stop(0);}
        assertEquals("failed",GuidanceClients.exchange("https://example.invalid",null,1,new JSONObject()).getString("status"));
    }
    @Test void tenGenerationWindowsNoTerminalCallsAndOffIsIdentical()throws Exception{
        RunConfig c=new RunConfig("C0",12,21,.7,.5);c.seed=101;RunResult baseline=new SearchRunner().run(c);
        OnlineControlOptions online=new OnlineControlOptions();online.monitor=true;AtomicInteger requests=new AtomicInteger();
        OnlineControlSession session=new OnlineControlSession(online,norm(),c,temp.resolve("control"),new JSONObject(),e->{requests.incrementAndGet();return new JSONObject();});
        RunResult monitored=new SearchRunner().run(c,session);assertEquals(JSON.toJSONString(baseline.population),JSON.toJSONString(monitored.population));assertEquals(2,((Number)monitored.onlineControl.get("decisionCount")).intValue());assertEquals(0,requests.get());
        for(int g:new int[]{100,150,200})assertEquals(g/10-1,(g-1)/10);
        GuidanceOptions options=new GuidanceOptions();options.mode="numeric";AtomicInteger generated=new AtomicInteger();
        GuidanceSession guide=new GuidanceSession(options,norm(),c,temp.resolve("guide"),new JSONObject(),new GuidanceEvidence("fixture"),task->{generated.incrementAndGet();JSONObject fail=new JSONObject();fail.put("status","failed");return fail;},null);
        RunResult guided=new SearchRunner().run(c,null,guide);assertEquals(21,guided.completedGenerations);assertEquals(12,guided.populationSize);assertEquals(2,((Number)guided.guidance.get("windowCount")).intValue());assertEquals(JSON.toJSONString(baseline.population),JSON.toJSONString(guided.population));assertEquals(baseline.evaluations,guided.evaluations);assertTrue(GuidanceSession.auditComplete((JSONObject)guided.guidance));
        Files.write(temp.resolve("guide/current-model.dep"),"tampered".getBytes(StandardCharsets.UTF_8));assertFalse(GuidanceSession.auditComplete((JSONObject)guided.guidance));
    }
    @Test void sequentialChoicesRespectNonNumericChoiceKeepAndReplay()throws Exception{
        int[] base=baseGenes();RunResult.SolutionRecord b=evaluate(base);Chromosome p=SearchRunner.chromosome(base);Service.calculateObjectiveValues(p,SearchRunner.objectives());Population parent=new Population(Arrays.asList(p));
        GuidanceEvidence m=new GuidanceEvidence("fixture");GuidanceOptions o=new GuidanceOptions();o.mode="jev";o.maxTasks=1;o.lossTolerance=10;
        AtomicInteger calls=new AtomicInteger();
        GuidanceSession.Generator generator=task->{JSONObject answer=new JSONObject();answer.put("status","success");JSONArray proposals=new JSONArray();
            for(int n=0;n<2;n++){JSONObject proposal=m.mapping(b);proposal.remove("serviceCount");proposal.put("id","p"+n);proposal.getJSONObject("entities").put("entity:"+n,1-base[DiagramParser.numofUC+n]);proposals.add(proposal);}answer.put("candidates",proposals);return answer;};
        GuidanceSession.Chooser chooser=e->{JSONObject a=new JSONObject();a.put("status","success");a.put("choice",calls.incrementAndGet()==1?"candidate-2":"KEEP");return a;};
        JSONObject diag=new JSONObject();int metric=GuidanceEvidence.weakness(Arrays.asList(b),norm(),diag);
        GuidanceSession.Evaluator ev=genes->{RunResult.SolutionRecord r=evaluate(genes);r.objectives=b.objectives.clone();r.metrics=b.metrics.clone();r.objectives[metric]+=.01;r.metrics[metric]+=(metric==0?-.01:.01);return r;};
        RunConfig cfg=new RunConfig("C0",12,21,.7,.5);
        GuidanceSession actual=new GuidanceSession(o,norm(),cfg,temp.resolve("actual"),new JSONObject(),m,generator,chooser);
        List<int[]> selected=actual.afterSelection(10,parent,ev);assertEquals(1,selected.size());assertEquals(2,calls.get());assertEquals(2,actual.candidateEvaluations);actual.recordRetention(11,new Population(Arrays.asList(SearchRunner.chromosome(selected.get(0)))));actual.finish();
        JSONObject window=FilesIO.object(temp.resolve("actual/window-0010.json"));assertEquals("candidate-2",window.getJSONArray("selected").getJSONObject(0).getString("id"));assertEquals(1,window.getIntValue("retainedCount"));
        GuidanceOptions replay=JSON.parseObject(JSON.toJSONString(o),GuidanceOptions.class);replay.mode="replay";replay.replayFile=temp.resolve("actual/guidance-windows.jsonl").toString();
        GuidanceSession repeated=new GuidanceSession(replay,norm(),cfg,temp.resolve("replay"),new JSONObject(),m,t->{throw new AssertionError("No live generation during replay");},e->{throw new AssertionError("No live JEV during replay");});
        assertArrayEquals(selected.get(0),repeated.afterSelection(10,parent,ev).get(0));repeated.finish();
    }
    @Test void generationProtocolRejectsTruncationJsonAndWrongModel(){
        GuidanceOptions o=new GuidanceOptions();JSONObject body=new JSONObject();body.put("model",o.generator.model);JSONArray choices=new JSONArray();JSONObject choice=new JSONObject();choice.put("finish_reason","stop");JSONObject message=new JSONObject();message.put("content","{\"candidates\":[{\"id\":\"p1\"}]}");choice.put("message",message);choices.add(choice);body.put("choices",choices);
        JSONObject success=new JSONObject();success.put("status","success");success.put("response",body);
        assertEquals("success",GuidanceClients.decodeGeneration(JSON.parseObject(success.toJSONString()),o).getString("status"));
        for(String reason:Arrays.asList("length","content_filter")){choice.put("finish_reason",reason);assertEquals("failed",GuidanceClients.decodeGeneration(JSON.parseObject(success.toJSONString()),o).getString("status"));}
        choice.put("finish_reason","stop");for(String raw:Arrays.asList("not JSON","{}","{\"candidates\":[]}")){message.put("content",raw);assertEquals("failed",GuidanceClients.decodeGeneration(JSON.parseObject(success.toJSONString()),o).getString("status"));}
        message.put("content","{\"candidates\":[{}]}");body.put("model","other-model");assertEquals("failed",GuidanceClients.decodeGeneration(JSON.parseObject(success.toJSONString()),o).getString("status"));
    }
    @Test void nominalRunsActuallyComplete100150200With9_14_19Windows()throws Exception{
        for(int generations:new int[]{100,150,200}){
            RunConfig cfg=new RunConfig("C0",6,generations,.7,.5);OnlineControlOptions o=new OnlineControlOptions();o.mode="jev";AtomicInteger count=new AtomicInteger();
            OnlineControlSession control=new OnlineControlSession(o,norm(),cfg,temp.resolve("nominal-"+generations),new JSONObject(),e->{assertTrue(e.getIntValue("currentGeneration")<generations);count.incrementAndGet();JSONObject r=new JSONObject();r.put("status","success");r.put("choice","KEEP");return r;});
            RunResult r=new SearchRunner().run(cfg,control);assertEquals(generations,r.completedGenerations);assertEquals((generations-1)/10,count.get());assertEquals((3L*generations+1)*6,r.evaluations);
        }
    }
    @Test void choiceFailureRetainsEarlierSelectionsAndReferenceIsNotReset()throws Exception{
        int[] genes=baseGenes();RunResult.SolutionRecord b=evaluate(genes);Chromosome c=SearchRunner.chromosome(genes);Service.calculateObjectiveValues(c,SearchRunner.objectives());Population parent=new Population(Arrays.asList(c));GuidanceEvidence m=new GuidanceEvidence("fixture");GuidanceOptions o=new GuidanceOptions();o.mode="jev";o.maxTasks=1;o.lossTolerance=10;AtomicInteger choices=new AtomicInteger();
        GuidanceSession.Generator generator=task->{JSONObject a=new JSONObject();a.put("status","success");JSONArray proposals=new JSONArray();for(int j=0;j<2;j++){JSONObject p=m.mapping(b);p.remove("serviceCount");p.put("id","p"+j);p.getJSONObject("entities").put("entity:"+j,1-genes[DiagramParser.numofUC+j]);proposals.add(p);}a.put("candidates",proposals);return a;};
        int metric=GuidanceEvidence.weakness(Arrays.asList(b),norm(),new JSONObject());GuidanceSession.Evaluator evaluator=g->{RunResult.SolutionRecord r=evaluate(g);r.objectives=b.objectives.clone();r.metrics=b.metrics.clone();r.objectives[metric]+=.01;return r;};
        GuidanceSession guide=new GuidanceSession(o,norm(),new RunConfig("C0",12,21,.7,.5),temp.resolve("partial"),new JSONObject(),m,generator,e->{JSONObject a=new JSONObject();if(choices.incrementAndGet()%2==1){a.put("status","success");a.put("choice","candidate-1");}else a.put("status","failed");return a;});
        assertEquals(1,guide.afterSelection(10,parent,evaluator).size());assertEquals(1,guide.afterSelection(20,parent,evaluator).size());guide.finish();
        JSONObject first=FilesIO.object(temp.resolve("partial/window-0010.json")),second=FilesIO.object(temp.resolve("partial/window-0020.json"));assertEquals(GuidanceSession.hash(first.get("fixedReference")),GuidanceSession.hash(second.get("fixedReference")));assertEquals("CHOICE_FAILED",first.getString("selectionStopped"));assertEquals(2,first.getJSONArray("selectionCalls").size());
    }
    @Test void missingDerivedAccessIsAnErrorButLegalRemoteAccessIsNot(){
        GuidanceEvidence m=new GuidanceEvidence("fixture");RunResult.SolutionRecord b=evaluate(baseGenes());assertFalse(m.consistency(b).getBooleanValue("deterministicError"));
        RunResult.SolutionRecord broken=JSON.parseObject(JSON.toJSONString(b),RunResult.SolutionRecord.class);
        for(Map<String,Object> service:broken.services)((Collection<?>)service.get("requiredEntityInterfaces")).clear();
        assertTrue(m.consistency(broken).getBooleanValue("deterministicError"));
    }
    @Test void timeoutAndHttpFailureHaveNoRetryOrFallback()throws Exception{
        AtomicInteger calls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();try{if(exchange.getRequestURI().getPath().contains("slow"))Thread.sleep(150);byte[] b="{\"error\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,b.length);exchange.getResponseBody().write(b);}catch(Exception ignored){}finally{exchange.close();}});server.start();
        try{
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();JSONObject http=GuidanceClients.exchange(endpoint,"fixture",2000,new JSONObject());assertEquals("failed",http.getString("status"));assertEquals(503,http.getIntValue("httpStatus"));assertEquals(1,calls.get());
            long start=System.nanoTime();JSONObject timeout=GuidanceClients.exchange(endpoint+"/slow","fixture",30,new JSONObject());assertEquals("failed",timeout.getString("status"));assertTrue((System.nanoTime()-start)/1e6<1500);assertEquals(2,calls.get());
        }finally{server.stop(0);}
    }
    @Test void newExperimentRejectsLegacyWindowAndHiddenCandidateBudget(){
        ExperimentConfig cfg=new ExperimentConfig();ExperimentConfig.SystemInput input=new ExperimentConfig.SystemInput();input.id="CoCoME";input.input="cocome.remodel";cfg.systems.add(input);
        cfg.onlineControl.intervalGenerations=30;assertThrows(IllegalArgumentException.class,cfg::validate);cfg.onlineControl.intervalGenerations=10;cfg.candidates.get(0).evaluationBudget=1000;assertThrows(IllegalArgumentException.class,cfg::validate);
        GuidanceOptions o=new GuidanceOptions();o.maxSelected=5;assertDoesNotThrow(o::validate);
    }
    @Test void choicePromptDefinesAllMetricDirectionsExplicitly(){
        JSONObject evidence=new JSONObject();evidence.put("choices",Collections.singletonMap("KEEP","keep"));JSONObject request=GuidanceClients.choiceRequest(evidence,"fixed-model");String text=request.getJSONObject("questions").getJSONObject("configuration").getString("instructions");
        assertTrue(text.contains("IFN is minimized"));assertTrue(text.contains("Instability and UCM are maximized"));assertTrue(text.contains("lower-is-better"));assertTrue(text.contains("provided/(provided+required)"));assertTrue(text.contains("Cross-service access is not automatically inconsistent"));assertEquals(5,SelectionEvidence.metricDefinitions().size());
        assertTrue(text.contains("isolated=0"));assertFalse(text.contains("isolated=1"));
        ExperimentConfig.JevOptions missing=new ExperimentConfig.JevOptions();JSONObject failure=GuidanceClients.choose(missing,evidence);assertEquals("failed",failure.getString("status"));assertFalse(failure.getBooleanValue("attempted"));
    }
    @Test void relaxedHalfUnitToleranceAcceptsBoundaryButKeepsOtherGates(){
        assertEquals(.5,new GuidanceOptions().lossTolerance);
        RunResult.SolutionRecord b=new RunResult.SolutionRecord(),r=new RunResult.SolutionRecord(),c=new RunResult.SolutionRecord();
        b.objectives=new double[]{-5,.8,.5,.5,.5};r.objectives=b.objectives.clone();c.objectives=b.objectives.clone();c.objectives[0]=-4;c.objectives[1]=.3;
        assertNotNull(GuidanceSession.gate(c,b,r,0,norm(),.02));
        assertNull(GuidanceSession.gate(c,b,r,0,norm(),.5));
        c.objectives[1]=.2999;assertNotNull(GuidanceSession.gate(c,b,r,0,norm(),.5));
        c.objectives[1]=.3;c.objectives[0]=-5;assertNotNull(GuidanceSession.gate(c,b,r,0,norm(),.5));
    }
}

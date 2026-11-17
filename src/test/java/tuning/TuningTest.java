package tuning;
import GA.*;
import Evaluation.*;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.Service;
import com.debacharya.nsgaii.datastructure.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TuningTest {
    @TempDir Path temp;
    @BeforeEach void fixture() {
        DiagramParser.numofUC=2;DiagramParser.numofEntity=2;
        DiagramParser.instancegetUCtoE=new ArrayList<>(Arrays.asList(new double[]{1,0},new double[]{0,1}));
        DiagramParser.instancegetEtoE=new ArrayList<>(Arrays.asList(new double[]{0,1},new double[]{1,0}));
        DiagramParser.DataSet=new ArrayList<>(Arrays.asList(new double[]{1,.2},new double[]{.2,1}));
        DiagramParser.instancegetPostoUC=new HashMap<>();DiagramParser.instancegetPostoUC.put(0,"u0");DiagramParser.instancegetPostoUC.put(1,"u1");
        DiagramParser.instancegetPostoEntity=new HashMap<>();DiagramParser.instancegetPostoEntity.put(0,"e0");DiagramParser.instancegetPostoEntity.put(1,"e1");
        DiagramParser.instancegetUCtoOp=new HashMap<>();DiagramParser.instancegetUCtoOp.put("u0",new HashSet<>(Arrays.asList("o0")));DiagramParser.instancegetUCtoOp.put("u1",new HashSet<>(Arrays.asList("o1")));
        DiagramParser.instancegetOPstoEntity=new HashMap<>();DiagramParser.instancegetOPstoEntity.put("o0",new HashSet<>(Arrays.asList("e0")));DiagramParser.instancegetOPstoEntity.put("o1",new HashSet<>(Arrays.asList("e1")));
        DiagramParser.instancegetRelatedEntity=new ArrayList<>();
        DiagramParser.instancegetActortoUC=new HashMap<>();DiagramParser.instancegetActortoUC.put("actor",new HashSet<>(Arrays.asList("u0","u1")));
        Map<String,Set<String>> attributes=new HashMap<>();attributes.put("e0",new HashSet<>(Arrays.asList("common","a")));attributes.put("e1",new HashSet<>(Arrays.asList("common","b")));
        PaperMetrics.initialize(attributes,Collections.emptySet());
    }
    private Chromosome chromosome(int... genes) {
        List<IntegerAllele> alleles=new ArrayList<>();for(int g:genes)alleles.add(new IntegerAllele(g));Chromosome c=new Chromosome(alleles);
        Service.calculateObjectiveValues(c,SearchRunner.objectives());return c;
    }
    @Test void fivePaperObjectiveFixtures() {
        // Two services each require and provide one entity interface: total 4, average 2.
        Chromosome split=chromosome(0,1,0,1);
        assertEquals(-2,split.getObjectiveValue(0),1e-12);
        assertArrayEquals(new double[]{0,-.3333,.5,-.2},new double[]{split.getObjectiveValue(1),split.getObjectiveValue(2),split.getObjectiveValue(3),split.getObjectiveValue(4)},1e-12);
        Chromosome merged=chromosome(0,0,0,0);
        assertArrayEquals(new double[]{0,0,.3333,0,.2},merged.getObjectiveValues().stream().mapToDouble(x->x).toArray(),1e-12);
    }
    @Test void seededSearchAndBudget() {
        RunConfig config=new RunConfig("C0",6,3,.7,.5);config.seed=11;
        RunResult a=new SearchRunner().run(config),b=new SearchRunner().run(config);
        assertEquals(60,a.evaluations);assertEquals(3,a.completedGenerations);
        assertEquals(JSON.toJSONString(a.front),JSON.toJSONString(b.front));
        assertEquals(JSON.toJSONString(a.population),JSON.toJSONString(b.population));
        RunConfig capped=new RunConfig("C1",12,6,.7,.5);capped.evaluationBudget=60;
        RunResult c=new SearchRunner().run(capped);assertEquals(48,c.evaluations);assertEquals(12,c.unusedBudget);assertEquals(12,c.population.size());
        assertTrue(c.front.size()>0);for(RunResult.SolutionRecord p:c.front)assertTrue(SearchRunner.valid(p.chromosome));
    }
    @Test void budgetRejectsPartialInitialization(){RunConfig c=new RunConfig("C0",12,6,.7,.5);c.evaluationBudget=47;assertThrows(IllegalArgumentException.class,c::validate);}
    @Test void labelsAndValidity() {
        assertArrayEquals(new int[]{0,1,0,1},SearchRunner.canonical(new int[]{7,4,7,4}));
        assertFalse(SearchRunner.valid(new int[]{0,0,0,1}));assertFalse(SearchRunner.valid(new int[]{0,1,-1,0}));assertFalse(SearchRunner.valid(new int[]{0}));
    }
    private RunResult.SolutionRecord row(double... values) {
        RunResult.SolutionRecord r=new RunResult.SolutionRecord();r.objectives=values;r.chromosome=new int[]{0,0,0,0};return r;
    }
    @Test void fiveDimensionalHypervolumeWithoutClipping() {
        Quality.Normalization n=new Quality.Normalization();Arrays.fill(n.max,1);
        RunResult.SolutionRecord p=row(-.2,-.2,-.2,-.2,-.2);
        assertEquals(Math.pow(.9,5),Quality.hv(Arrays.asList(p),n),1e-10);
        assertEquals(Math.pow(.9,5),Quality.hv(Arrays.asList(p,p),n),1e-10);
        assertEquals(0,Quality.hv(Arrays.asList(row(-1.2,0,0,0,0)),n),1e-10);
        assertEquals(Math.pow(1.3,5),Quality.hv(Arrays.asList(row(.2,.2,.2,.2,.2)),n),1e-10);
        assertEquals(0,Quality.hv(Collections.emptyList(),n));
        // Two overlapping boxes: union = 2*.9^4*.7 - .9^3*.7^2.
        assertEquals(2*Math.pow(.9,4)*.7-Math.pow(.9,3)*.7*.7,
            Quality.hv(Arrays.asList(row(-.2,-.2,-.2,-.2,-.4),row(-.2,-.2,-.2,-.4,-.2)),n),1e-10);
    }
    @Test void zeroSpanDominanceAndRepresentative() {
        RunResult r=new RunResult();r.population.add(row(1,1,1,1,1));Quality.Normalization n=Quality.fit(Arrays.asList(r));
        for(boolean zero:n.zeroSpan)assertTrue(zero);
        assertEquals(1,Quality.front(Arrays.asList(row(0,0,0,0,0),row(1,1,1,1,1))).size());
        assertSame(r.population.get(0),Quality.representative(r.population,n));
    }
    @Test void sourceDirectoryUsesNativePathsAndFreshModelState()throws Exception {
        Files.write(temp.resolve("SampleController.java"), ("public class SampleController {\n"+
            " public Object browse() {\n return fooService.load();\n }\n}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(temp.resolve("FooService.java"),("public class FooService {\n"+
            " public Object load() {\n return bookMapper.findBooks();\n }\n}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(temp.resolve("Book.java"),"@Data\npublic class Book {\n}\n".getBytes(StandardCharsets.UTF_8));
        ModelLoader.load(temp);
        assertEquals(1,DiagramParser.numofUC);assertEquals(1,DiagramParser.numofEntity);
        assertEquals("browse",DiagramParser.instancegetPostoUC.get(0));
        RunConfig config=new RunConfig("C0",4,2,.7,.5);
        RunResult result=new SearchRunner().run(config);assertFalse(result.front.isEmpty());
    }
    @Test void seedConfigRejectsOverlapAndDuplicates() {
        ExperimentConfig c=new ExperimentConfig();ExperimentConfig.SystemInput s=new ExperimentConfig.SystemInput();s.id="one";s.input="one.remodel";c.systems.add(s);c.validate();
        c.validationSeeds=Arrays.asList(11L);assertThrows(IllegalArgumentException.class,c::validate);
        c.validationSeeds=Arrays.asList(101L);c.calibrationSeeds=Arrays.asList(11L,11L);assertThrows(IllegalArgumentException.class,c::validate);
    }
    private JSONObject evidence() {
        JSONObject e=new JSONObject(),candidates=new JSONObject();e.put("candidates",candidates);e.put("metricVersion",SearchRunner.METRICS);
        Quality.Normalization n=new Quality.Normalization();n.min=new double[]{0,-1,-1,-1,-1};n.max=new double[]{10,0,1,0,1};
        e.put("normalization",JSON.parseObject(JSON.toJSONString(n)));e.put("calibrationSeeds",Arrays.asList(11L));
        for(int i=0;i<6;i++){
            JSONObject c=new JSONObject();c.put("config",new RunConfig("C"+i,6,3,.7,.5));c.put("meanHv",i==2 || i==3?1:.5);
            JSONObject trial=new JSONObject(),rep=new JSONObject();rep.put("metrics",new double[]{2+i,.8-i*.05,.1,.6,.4});rep.put("serviceCount",3);
            trial.put("seed",11L);trial.put("hv",c.get("meanHv"));trial.put("representative",rep);JSONArray trials=new JSONArray();trials.add(trial);c.put("trials",trials);
            candidates.put("C"+i,c);
        }return e;
    }
    @Test void numericTieAndMockAreExplicit()throws Exception {
        assertEquals("C2",new Selectors.NumericSelector().select(evidence(),temp.resolve("numeric.json")).getString("choice"));
        JSONObject mock=new Selectors.MockJevSelector().select(evidence(),temp.resolve("mock.json"));assertTrue(mock.getBooleanValue("mock"));assertEquals("C0",mock.getString("choice"));
    }
    @Test void jevRetriesInvalidChoiceAndCountsUsage()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        ExperimentConfig.JevOptions options=new ExperimentConfig.JevOptions();options.model="jev-test-fixed";
        Selectors.JevSelector client=new Selectors.JevSelector(options,"test-key") {
            @Override protected HttpURLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    java.io.ByteArrayOutputStream request=new java.io.ByteArrayOutputStream();
                    public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
                    @Override public java.io.OutputStream getOutputStream(){return request;}
                    @Override public int getResponseCode(){return 200;}
                    @Override public java.io.InputStream getInputStream() {
                        JSONObject body=JSON.parseObject(new String(request.toByteArray(),StandardCharsets.UTF_8));
                        assertEquals("choice",body.getJSONObject("questions").getJSONObject("configuration").getString("type"));
                        String choice=calls.incrementAndGet()==1?"C9":"C2";
                        JSONObject response=new JSONObject();response.put("model","jev-test-fixed");
                        JSONObject usage=new JSONObject();usage.put("input_tokens",10);usage.put("output_tokens",2);response.put("usage",usage);
                        JSONObject answer=new JSONObject();answer.put("type","choice");answer.put("choice",choice);
                        JSONObject answers=new JSONObject();answers.put("configuration",answer);response.put("answers",answers);
                        return new java.io.ByteArrayInputStream(response.toJSONString().getBytes(StandardCharsets.UTF_8));
                    }
                };
            }
        };
        JSONObject result=client.select(evidence(),temp.resolve("jev.json"));
        assertEquals("C2",result.getString("choice"));assertEquals(24,result.getLongValue("totalTokens"));assertEquals(2,calls.get());
        assertTrue(result.getDoubleValue("totalResponseMillis")>0);assertFalse(new String(Files.readAllBytes(temp.resolve("jev.json")),StandardCharsets.UTF_8).contains("test-key"));
    }
    @Test void timeoutIsRetriedAndUsageRemainsUnknown()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        ExperimentConfig.JevOptions options=new ExperimentConfig.JevOptions();options.model="jev-test-fixed";
        Selectors.JevSelector client=new Selectors.JevSelector(options,"test-key") {
            @Override protected HttpURLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
                    @Override public java.io.OutputStream getOutputStream()throws java.io.IOException {
                        calls.incrementAndGet();throw new java.net.SocketTimeoutException("test timeout");
                    }
                };
            }
        };
        Path audit=temp.resolve("timeout.json");assertThrows(java.io.IOException.class,()->client.select(evidence(),audit));
        JSONObject result=FilesIO.object(audit);assertEquals(2,calls.get());assertEquals("failed",result.getString("status"));
        assertNull(result.get("choice"));assertNull(result.get("totalTokens"));assertFalse(result.getBooleanValue("usageComplete"));
    }
    @Test void distinctDecisionSolutionsCanHaveOneObjectivePoint() {
        RunResult.SolutionRecord a=row(1,1,1,1,1),b=row(1,1,1,1,1);b.chromosome=new int[]{0,1,0,1};
        assertEquals(2,Quality.front(Arrays.asList(a,b)).size());assertEquals(1,SearchRunner.objectivePointCount(Arrays.asList(a,b)));
    }
    @Test void normalizationJsonRoundTrip() {
        RunResult r=new RunResult();r.population.add(row(1,1,1,1,1));Quality.Normalization n=Quality.fit(Arrays.asList(r));
        Quality.Normalization read=JSON.parseObject(JSON.toJSONString(n),Quality.Normalization.class);
        assertEquals(Quality.hv(r.population,n),Quality.hv(r.population,read));
    }
    @Test void missingKeyFailsWithoutFabricatedChoice() {
        ExperimentConfig.JevOptions options=new ExperimentConfig.JevOptions();options.model="jev-test-fixed";
        assertThrows(Exception.class,()->new Selectors.JevSelector(options,null).select(evidence(),temp.resolve("failure.json")));
    }
    @Test void pairedIntervalsRequireIndependentPairs() {
        assertNull(Statistics.paired(Arrays.asList(1.0)).get("ci95"));assertEquals(1,Statistics.paired(Arrays.asList(1.0,1.0)).getJSONArray("ci95").getDoubleValue(0));
    }
    private static byte[] readAll(java.io.InputStream in)throws java.io.IOException {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
    }
}

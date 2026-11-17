package tuning;

import GA.DiagramParser;
import Evaluation.PaperMetrics;
import Evaluation.QualityCalculate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperMetricsTest {
    @TempDir Path temp;
    Map<String,Set<String>> attributes;
    @SafeVarargs final <T> HashSet<T> set(T... values){return new HashSet<>(Arrays.asList(values));}
    ArrayList<ArrayList<Integer>> groups(int[]... values){ArrayList<ArrayList<Integer>> out=new ArrayList<>();for(int[] row:values){ArrayList<Integer> list=new ArrayList<>();for(int x:row)list.add(x);out.add(list);}return out;}
    void model(int u,int e) {
        DiagramParser.numofUC=u;DiagramParser.numofEntity=e;
        DiagramParser.instancegetPostoUC=new HashMap<>();DiagramParser.instancegetUCPos=new HashMap<>();
        DiagramParser.instancegetPostoEntity=new HashMap<>();DiagramParser.instancegetEntityPos=new HashMap<>();
        DiagramParser.instancegetUCtoOp=new HashMap<>();DiagramParser.instancegetOPstoEntity=new HashMap<>();
        DiagramParser.instancegetActortoUC=new HashMap<>();DiagramParser.instancegetUCtoE=new ArrayList<>();DiagramParser.instancegetEtoE=new ArrayList<>();
        DiagramParser.instancegetRelatedEntity=new ArrayList<>();DiagramParser.entities=new HashSet<>();attributes=new HashMap<>();
        for(int i=0;i<u;i++){String name="u"+i;DiagramParser.instancegetPostoUC.put(i,name);DiagramParser.instancegetUCPos.put(name,i);DiagramParser.instancegetUCtoOp.put(name,new HashSet<>());DiagramParser.instancegetUCtoE.add(new double[e]);}
        for(int i=0;i<e;i++){String name="e"+i;DiagramParser.instancegetPostoEntity.put(i,name);DiagramParser.instancegetEntityPos.put(name,i);DiagramParser.entities.add(name);attributes.put(name,new HashSet<>());DiagramParser.instancegetEtoE.add(new double[e]);}
    }
    @BeforeEach void fixture(){model(3,3);}
    void initialize(){PaperMetrics.initialize(attributes,Collections.emptySet());}
    @Test void chdUsesOrderedOffDiagonalPairsAndNSquaredNotNMinusOne() {
        DiagramParser.instancegetUCtoOp.put("u0",set("a","b"));DiagramParser.instancegetOPstoEntity.put("a",set("e0"));DiagramParser.instancegetOPstoEntity.put("b",set("e0"));
        DiagramParser.instancegetUCtoOp.put("u1",set("a"));initialize();
        // u0 and u1 share operation a: the service operation SET still contains only a,b.
        double[] r=PaperMetrics.evaluate(groups(new int[]{0,1},new int[]{2}),groups(new int[]{0,1},new int[]{2}));
        assertEquals(.25,r[1],1e-12); // first service 2/2^2=.5; empty-operation service 0; average .25
        DiagramParser.instancegetUCtoOp.put("u0",set("a"));initialize();
        assertEquals(0,PaperMetrics.evaluate(groups(new int[]{0,1,2}),groups(new int[]{0,1,2}))[1]);
        DiagramParser.instancegetUCtoOp.put("u0",set("a","b"));DiagramParser.instancegetOPstoEntity.put("a",new HashSet<>());DiagramParser.instancegetOPstoEntity.put("b",new HashSet<>());initialize();
        assertEquals(0,PaperMetrics.evaluate(groups(new int[]{0,1,2}),groups(new int[]{0,1,2}))[1]);
    }
    @Test void cmqUsesAttributeJaccardAndGlobalPairMeans() {
        attributes.put("e0",set("id","name"));attributes.put("e1",set("id","price"));attributes.put("e2",set("title"));initialize();
        assertEquals(1.0/3,PaperMetrics.entitySimilarity(0,1),1e-12);
        assertEquals(1.0/3,PaperMetrics.evaluate(groups(new int[]{0,1},new int[]{2}),groups(new int[]{0,1},new int[]{2}))[2],1e-12);
        assertEquals(-1.0/6,PaperMetrics.evaluate(groups(new int[]{0,1},new int[]{2}),groups(new int[]{0,2},new int[]{1}))[2],1e-12);
        assertEquals(1.0/9,PaperMetrics.evaluate(groups(new int[]{0,1,2}),groups(new int[]{0,1,2}))[2],1e-12);
        assertEquals(-1.0/9,PaperMetrics.evaluate(groups(new int[]{0},new int[]{1},new int[]{2}),groups(new int[]{0},new int[]{1},new int[]{2}))[2],1e-12);
    }
    @Test void ucmUsesBinaryActorsExplicitScenariosAndCosineWithPaperWeights() {
        DiagramParser.instancegetActortoUC.put("a",set("u0","u1"));DiagramParser.instancegetActortoUC.put("b",set("u0","u1"));
        DiagramParser.instancegetUCtoE.get(0)[0]=1;DiagramParser.instancegetUCtoE.get(1)[0]=1;DiagramParser.instancegetUCtoE.get(1)[1]=1;
        PaperMetrics.initialize(attributes,set(PaperMetrics.pair("u0","u1")));
        double expected=.2+.3+.5/Math.sqrt(2);
        assertEquals(expected,PaperMetrics.useCaseSimilarity(0,1),1e-12);assertEquals(0,PaperMetrics.useCaseSimilarity(0,2));
        double[] r=PaperMetrics.evaluate(groups(new int[]{0,1},new int[]{2}),groups(new int[]{0,1},new int[]{2}));assertEquals(expected,r[4],1e-12);
        assertEquals(-expected/3,PaperMetrics.evaluate(groups(new int[]{0},new int[]{1},new int[]{2}),groups(new int[]{0},new int[]{1},new int[]{2}))[4],1e-12);
        assertEquals(expected/3,PaperMetrics.evaluate(groups(new int[]{0,1,2}),groups(new int[]{0,1,2}))[4],1e-12);
    }
    @Test void ucmWeightsAllPairsRatherThanAveragingPerServiceMeans() {
        model(5,1);DiagramParser.instancegetActortoUC.put("a",set("u0","u1"));DiagramParser.instancegetActortoUC.put("b",set("u3","u4"));initialize();
        double[] r=PaperMetrics.evaluate(groups(new int[]{0,1,2},new int[]{3,4}),groups(new int[]{0},new int[]{}));
        assertEquals(.1,r[4],1e-12); // four internal pairs, two have correlation .2
    }
    @Test void zeroVectorsEmptyPairsAndSingletonsAreFiniteWithoutBonus() {
        initialize();assertEquals(0,PaperMetrics.jaccard(Collections.emptySet(),Collections.emptySet()));
        double[] r=PaperMetrics.evaluate(groups(new int[]{0},new int[]{1},new int[]{2}),groups(new int[]{0},new int[]{1},new int[]{2}));
        assertArrayEquals(new double[]{0,0,0,0,0},Arrays.copyOf(r,5),1e-12);
        for(double x:r)assertTrue(Double.isFinite(x));
    }
    @Test void interfaceMeansExcludeUserOperationsAndHandleProviderReceiverAndIsolated() {
        DiagramParser.instancegetUCtoE.get(0)[1]=1;DiagramParser.instancegetUCtoOp.put("u0",set("externalOne","externalTwo"));initialize();
        double[] r=PaperMetrics.evaluate(groups(new int[]{0},new int[]{1},new int[]{2}),groups(new int[]{0},new int[]{1},new int[]{2}));
        assertEquals(2.0/3,r[0],1e-12);assertEquals(1.0/3,r[3],1e-12);
        r=PaperMetrics.evaluate(groups(new int[]{0,1,2}),groups(new int[]{0,1,2}));assertEquals(0,r[0]);assertEquals(0,r[3]);
    }
    @Test void incompleteOrDuplicatePartitionsAndMissingAttributesAreRejected() {
        initialize();assertThrows(IllegalArgumentException.class,()->PaperMetrics.evaluate(groups(new int[]{0,0,1,2}),groups(new int[]{0,1,2})));
        assertThrows(IllegalArgumentException.class,()->PaperMetrics.evaluate(groups(new int[]{0,1}),groups(new int[]{0,1,2})));
        attributes.remove("e2");assertThrows(IllegalArgumentException.class,()->PaperMetrics.initialize(attributes,Collections.emptySet()));
    }
    @Test void cacheIsClearedWhenSamePartitionHasNewAttributeMetadata() {
        initialize();ArrayList<ArrayList<Integer>> g=groups(new int[]{0,1},new int[]{2});assertEquals(0,PaperMetrics.evaluate(g,g)[2]);
        attributes.put("e0",set("id"));attributes.put("e1",set("id"));initialize();assertEquals(1,PaperMetrics.evaluate(g,g)[2]);
    }
    @Test void remodelMetadataRetainsOnlyDeclaredAttributesAndExplicitRelations()throws Exception {
        String source="UseCaseModel X {\n UC::u0() include u1 @Description(\"http://not-a-comment/}\")\n UC::u1() extend u0\n UC::u2()\n}\nDomainModel X {\n Entity e0 {\n @Description(\"name\") id : Integer\n sex : Sex[M|F]\n [Refer]\n friend : e1 Association\n [INV]\n inv Stuff : true\n }\n Entity e1 {\n id : String\n }\n Entity e2 {\n }\n}";
        Path input=temp.resolve("metadata.remodel");Files.write(input,source.getBytes(StandardCharsets.UTF_8));MetricMetadata.load(input);
        assertEquals(0.5,PaperMetrics.entitySimilarity(0,1),1e-12); // id matches despite differing declared types; sex is separate
        assertEquals(.3,PaperMetrics.useCaseSimilarity(0,1),1e-12); // reverse/include+extend edges remain one binary relation
        Map<?,?> names=(Map<?,?>)MetricMetadata.snapshot().get("entityAttributeNames");assertEquals(set("id","sex"),names.get("e0"));
    }
    @Test void javaMetadataUsesNonStaticNonReferenceDeclaredAttributeNames()throws Exception {
        model(2,2);
        Files.write(temp.resolve("e0.java"),"class e0 { int id; e1 friend; static String ignored; }".getBytes(StandardCharsets.UTF_8));
        Files.write(temp.resolve("e1.java"),"class e1 { long id; String name; }".getBytes(StandardCharsets.UTF_8));
        MetricMetadata.load(temp);assertEquals(.5,PaperMetrics.entitySimilarity(0,1),1e-12);assertEquals(0,PaperMetrics.useCaseSimilarity(0,1));
    }
    @Test void oldMetricConfigurationCannotReuseFrozenCalibration() {
        ExperimentConfig cfg=new ExperimentConfig();ExperimentConfig.SystemInput s=new ExperimentConfig.SystemInput();s.id="case";s.input="input.remodel";cfg.systems.add(s);cfg.metricVersion="legacy-ifn-average-v1";
        assertThrows(IllegalArgumentException.class,cfg::validate);assertEquals(PaperMetrics.VERSION,SearchRunner.METRICS);
        cfg.metricVersion="paper-ieee-eq5-21-v1";
        assertThrows(IllegalArgumentException.class,cfg::validate);
    }
    @Test void commentedOutEntityCannotSilentlyBecomeAnEmptyAttributeSet()throws Exception {
        Path input=temp.resolve("commented.remodel");Files.write(input,("DomainModel X {\n Entity e0 {}\n Entity e1 {}\n // Entity e2 {}\n}\n").getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->MetricMetadata.load(input));
        assertTrue(error.getMessage().contains("no active attribute declaration: e2"));
    }
}

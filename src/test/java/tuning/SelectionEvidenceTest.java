package tuning;

import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionEvidenceTest {
    @TempDir Path temp;
    private JSONObject fixture(boolean multi) {
        JSONObject full=new JSONObject(),candidates=new JSONObject();full.put("candidates",candidates);full.put("metricVersion",SearchRunner.METRICS);
        Quality.Normalization n=new Quality.Normalization();n.min=new double[]{0,-1,-1,-1,-1};n.max=new double[]{10,0,1,0,1};
        full.put("normalization",JSON.parseObject(JSON.toJSONString(n)));full.put("calibrationSeeds",multi?Arrays.asList(11L,23L):Arrays.asList(11L));
        full.put("validationSeeds",Arrays.asList(101L));full.put("testResults","DO_NOT_LEAK_TEST_DATA");
        for(int i=0;i<6;i++){
            JSONObject c=new JSONObject();c.put("config",new RunConfig("C"+i,1000,600,.9,.25));JSONArray trials=new JSONArray();c.put("trials",trials);
            for(long seed:multi?new long[]{11,23}:new long[]{11}) {
                JSONObject t=new JSONObject(),r=new JSONObject();r.put("metrics",new double[]{2+i,.8-i*.05,.2+i*.01,.6,.4});r.put("serviceCount",3);
                t.put("representative",r);t.put("hv",.4+.05*i);t.put("seed",seed);trials.add(t);
            }
            c.put("meanHv",.4+.05*i);candidates.put("C"+i,c);
        }
        return JSON.parseObject(full.toJSONString());
    }
    @Test void ifnDirectionNamedValuesAndBounds() {
        JSONObject state=SelectionEvidence.build(fixture(false));
        JSONObject metrics=state.getJSONObject("candidates").getJSONObject("C0").getJSONObject("meanRepresentativeMetrics");
        JSONObject ifn=metrics.getJSONObject("IFN"),chd=metrics.getJSONObject("CHD");
        assertEquals("minimize",ifn.getString("rawDirection"));assertEquals(2,ifn.getDoubleValue("rawValue"));assertEquals(.2,ifn.getDoubleValue("normalizedLoss"),1e-12);
        assertEquals("maximize",chd.getString("rawDirection"));assertEquals(.2,chd.getDoubleValue("normalizedLoss"),1e-12);
        assertEquals(0,chd.getDoubleValue("rawCalibrationMin"),1e-12);assertEquals(1,chd.getDoubleValue("rawCalibrationMax"));
        assertEquals("minimize",chd.getString("normalizedLossDirection"));assertTrue(chd.getString("definition").contains("Jaccard"));
    }
    @Test void higherHvCanDegradeSeveralRepresentativeMetrics() {
        JSONObject state=SelectionEvidence.build(fixture(false));JSONObject c=state.getJSONObject("vsC0").getJSONObject("C5");
        assertEquals("improved",c.getJSONObject("hv").getString("status"));
        assertEquals("degraded",c.getJSONObject("metrics").getJSONObject("IFN").getString("status"));
        assertEquals("degraded",c.getJSONObject("metrics").getJSONObject("CHD").getString("status"));
        assertEquals("unchanged",c.getJSONObject("metrics").getJSONObject("Instability").getString("status"));
        assertEquals("improved",c.getJSONObject("metrics").getJSONObject("CMQ").getString("status"));
        assertEquals(15,state.getJSONArray("pairwiseComparisons").size());assertEquals(5,state.getJSONObject("vsC0").size());
    }
    @Test void zeroSpanUsesUnitScaleAndDoesNotClip() {
        JSONObject full=fixture(false);JSONObject n=full.getJSONObject("normalization");n.getJSONArray("min").set(0,2);n.getJSONArray("max").set(0,2);n.getJSONArray("zeroSpan").set(0,true);
        JSONObject state=SelectionEvidence.build(full),values=state.getJSONObject("candidates").getJSONObject("C5").getJSONObject("meanRepresentativeMetrics").getJSONObject("IFN");
        assertEquals(1,values.getDoubleValue("normalizationScale"));assertEquals(5,values.getDoubleValue("normalizedLoss"));assertTrue(values.getBooleanValue("zeroSpan"));
    }
    @Test void pairedSeedsAndMeansRemainDistinct() {
        JSONObject full=fixture(true),a=full.getJSONObject("candidates").getJSONObject("C0"),b=full.getJSONObject("candidates").getJSONObject("C1");
        a.getJSONArray("trials").getJSONObject(0).getJSONObject("representative").getJSONArray("metrics").set(0,2);
        a.getJSONArray("trials").getJSONObject(1).getJSONObject("representative").getJSONArray("metrics").set(0,4);
        for(Object o:b.getJSONArray("trials"))((JSONObject)o).getJSONObject("representative").getJSONArray("metrics").set(0,3);
        JSONObject state=SelectionEvidence.build(full),compare=state.getJSONObject("vsC0").getJSONObject("C1");
        assertEquals("unchanged",compare.getJSONObject("metrics").getJSONObject("IFN").getString("status"));
        assertEquals("degraded",compare.getJSONArray("perSeed").getJSONObject(0).getJSONObject("metrics").getJSONObject("IFN").getString("status"));
        assertEquals("improved",compare.getJSONArray("perSeed").getJSONObject(1).getJSONObject("metrics").getJSONObject("IFN").getString("status"));
        assertEquals(2,state.getIntValue("calibrationSeedCount"));
        b.getJSONArray("trials").getJSONObject(1).put("seed",99L);assertThrows(IllegalArgumentException.class,()->SelectionEvidence.build(full));
    }
    @Test void requestContainsDefinitionsTradeoffsAndNoTestEvidence() {
        JSONObject request=SelectionEvidence.request(fixture(false),"jev-test-fixed"),state=request.getJSONObject("state");
        String text=request.toJSONString();assertFalse(text.contains("DO_NOT_LEAK_TEST_DATA"));assertFalse(state.containsKey("validationSeeds"));
        assertEquals(5,state.getJSONArray("metricDefinitions").size());assertTrue(state.getString("stabilityEvidence").contains("cannot be assessed"));
        assertTrue(request.getJSONObject("questions").getJSONObject("configuration").getString("instructions").contains("Do not choose solely because HV is largest"));
        assertTrue(text.contains("normalizedLoss"));assertTrue(text.contains("pairwiseComparisons"));assertTrue(text.contains("four decimal places"));
    }
    @Test void oldSelectionCannotPassPolicyCacheCheck() {
        JSONObject record=new JSONObject();record.put("status","success");record.put("choice","C5");
        assertFalse(SelectionEvidence.currentRecord(record,"jev"));record.put("selectionPolicyVersion",SelectionEvidence.VERSION);assertTrue(SelectionEvidence.currentRecord(record,"jev"));
        assertFalse(SelectionEvidence.currentRecord(record,"numeric"));
    }
    @Test void compactTransportPreservesEveryMetricBoundAndPairwiseDelta() {
        JSONObject full=SelectionEvidence.build(fixture(false)),wire=SelectionEvidence.wireEvidence(full);
        assertEquals("compact-calibration-v1",wire.getString("evidenceFormatVersion"));
        for(int j=0;j<5;j++) {
            String name=SelectionEvidence.NAMES[j];JSONObject definition=wire.getJSONArray("metricDefinitions").getJSONObject(j);
            JSONObject original=full.getJSONObject("candidates").getJSONObject("C0").getJSONObject("meanRepresentativeMetrics").getJSONObject(name);
            for(String field:Arrays.asList("definition","rawDirection","normalizedLossDirection","normalizationMin","normalizationMax","rawCalibrationMin","rawCalibrationMax","zeroSpan")) {
                Object a=original.get(field),b=definition.get(field);
                if(a instanceof Number && b instanceof Number)assertEquals(((Number)a).doubleValue(),((Number)b).doubleValue(),0);
                else assertEquals(a,b);
            }
        }
        for(String id:full.getJSONObject("candidates").keySet()) {
            JSONObject a=full.getJSONObject("candidates").getJSONObject(id),b=wire.getJSONObject("candidates").getJSONObject(id);
            assertEquals(a.get("meanHv"),b.get("meanHv"));
            for(String name:SelectionEvidence.NAMES)for(String key:Arrays.asList("rawValue","normalizedLoss"))assertEquals(a.getJSONObject("meanRepresentativeMetrics").getJSONObject(name).get(key),b.getJSONObject("meanRepresentativeMetrics").getJSONObject(name).get(key));
        }
        for(int i=0;i<15;i++) {
            JSONObject a=full.getJSONArray("pairwiseComparisons").getJSONObject(i),b=wire.getJSONArray("pairwiseComparisons").getJSONObject(i);
            for(String name:SelectionEvidence.NAMES)for(String key:Arrays.asList("rawDelta","normalizedLossDelta","status"))assertEquals(a.getJSONObject("metrics").getJSONObject(name).get(key),b.getJSONObject("metrics").getJSONObject(name).get(key));
            assertEquals(a.getJSONObject("hv").get("rawDelta"),b.getJSONObject("hv").get("rawDelta"));
        }
        assertTrue(wire.toJSONString().length()<full.toJSONString().length());
    }
    @Test void compactTransportKeepsMultipleSeedComparisons() {
        JSONObject wire=SelectionEvidence.wireEvidence(SelectionEvidence.build(fixture(true)));
        for(Object o:wire.getJSONArray("pairwiseComparisons"))assertEquals(2,((JSONObject)o).getJSONArray("perSeed").size());
    }
    @Test void jevCanSelectLowerHvWithoutBeingOverriddenAndReportBothSides()throws Exception {
        for(String choice:Arrays.asList("C0","C3")) {
            ExperimentConfig.JevOptions options=new ExperimentConfig.JevOptions();options.model="jev-test-fixed";
            Selectors.JevSelector client=new Selectors.JevSelector(options,"dummy-test-credential") {
                protected HttpURLConnection openConnection(URL url) {
                    return new HttpURLConnection(url) {
                        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
                        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
                        public int getResponseCode(){return 200;}
                        public InputStream getInputStream(){return new ByteArrayInputStream(("{\"model\":\"jev-test-fixed\",\"answers\":{\"configuration\":{\"type\":\"choice\",\"choice\":\""+choice+"\",\"confidence\":0.5}}}").getBytes(StandardCharsets.UTF_8));}
                    };
                }
            };
            Path audit=temp.resolve(choice+".json");JSONObject record=client.select(fixture(false),audit);
            assertEquals(choice,record.getString("choice"));assertEquals(SelectionEvidence.VERSION,record.getString("selectionPolicyVersion"));
            assertEquals(5,record.getJSONObject("programExplanation").getJSONArray("comparisons").size());
            String report=new String(Files.readAllBytes(temp.resolve(choice+".json.analysis.md")),StandardCharsets.UTF_8);
            assertTrue(report.contains("选择：**"+choice+"**"));assertTrue(report.contains("退化"));assertTrue(report.contains("改善"));assertTrue(report.contains("不是 JEV 的文字解释"));
        }
    }
    @Test void oldMetricCalibrationIsRejectedAndOriginalChoiceIsPreserved()throws Exception {
        Path source=Paths.get("output/cocome-jev-real-20261031/experiment/CoCoME/calibration.json");
        Assumptions.assumeTrue(Files.exists(source),"Local CoCoME calibration artifact is optional outside this workspace");
        Path old=source.resolveSibling("selection-jev.json");byte[] before=Files.readAllBytes(old);
        assertThrows(IllegalArgumentException.class,()->SelectionEvidence.request(FilesIO.object(source),"jev-1.14.0"));
        assertArrayEquals(before,Files.readAllBytes(old));
    }
}

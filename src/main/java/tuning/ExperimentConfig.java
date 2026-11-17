package tuning;
import java.util.*;
public class ExperimentConfig {
    public String profile="formal";
    public String outputDirectory="output/jev-tuning";
    public String metricVersion=SearchRunner.METRICS;
    public String selector="mock";
    public List<Long> calibrationSeeds=new ArrayList<>(Arrays.asList(11L));
    public List<Long> validationSeeds=new ArrayList<>(Arrays.asList(101L,103L,107L,109L,113L,127L,131L,137L,139L,149L));
    public List<SystemInput> systems=new ArrayList<>();
    public List<RunConfig> candidates=defaults();
    public JevOptions jev=new JevOptions();
    public OnlineControlOptions onlineControl=new OnlineControlOptions();
    public String configurationVersion="nominal-six-10gen-v1";
    public GuidanceOptions guidance=new GuidanceOptions();
    public long evaluationBudget=0;
    public long workerTimeoutSeconds=86400;
    public static class SystemInput {public String id;public String input;}
    public static class JevOptions {
        public String endpoint="https://api.typesafe.ai/v1/systemone";
        public String model;
        public int timeoutMillis=60000;
        /** Local UTF-8 wire-size guard, not a claim about provider token limits. */
        public int guidanceMaxRequestBytes=24000;
    }
    public static List<RunConfig> defaults(){return new ArrayList<>(Arrays.asList(
        new RunConfig("C0",200,100,.7,.5),new RunConfig("C1",150,150,.7,.5),new RunConfig("C2",100,200,.7,.5),
        new RunConfig("C3",200,100,.5,.25),new RunConfig("C4",200,100,.9,.75),new RunConfig("C5",100,200,.9,.25)));}
    public void validate() {
        if(onlineControl==null)throw new IllegalArgumentException("Missing onlineControl options");
        onlineControl.validate();guidance.validate();
        if(onlineControl.intervalGenerations!=10)throw new IllegalArgumentException("New experiments require a shared 10-generation window");
        if(evaluationBudget!=0)throw new IllegalArgumentException("New experiments use nominal generations; evaluationBudget must be zero");
        if(!SearchRunner.METRICS.equals(metricVersion))throw new IllegalArgumentException("Unsupported metricVersion: "+metricVersion+"; use "+SearchRunner.METRICS+" and a fresh calibration/output directory");
        checkSeeds(calibrationSeeds);checkSeeds(validationSeeds);
        for(Long seed:calibrationSeeds)if(validationSeeds.contains(seed))throw new IllegalArgumentException("Calibration/test seed overlap");
        if(!Arrays.asList("mock","jev","numeric").contains(selector))throw new IllegalArgumentException("Unknown selector");
        if(systems.isEmpty())throw new IllegalArgumentException("No systems");
        Set<String> ids=new HashSet<>();for(SystemInput s:systems)
            if(s.id==null || !s.id.matches("[A-Za-z0-9_-]+") || !ids.add(s.id) || s.input==null)throw new IllegalArgumentException("Invalid/duplicate system ID");
        ids.clear();for(RunConfig c:candidates){c.validate();if(c.evaluationBudget!=0)throw new IllegalArgumentException("Candidate evaluationBudget must be zero in nominal mode");if(!ids.add(c.id))throw new IllegalArgumentException("Duplicate candidate");}
        if(candidates.size()!=6 || !ids.equals(new HashSet<>(Arrays.asList("C0","C1","C2","C3","C4","C5"))))throw new IllegalArgumentException("Expected C0-C5");
        if(evaluationBudget<0 || workerTimeoutSeconds<1 || jev.timeoutMillis<1 || jev.guidanceMaxRequestBytes<1024)throw new IllegalArgumentException("Invalid budget/timeout/request size");
        if("formal".equals(profile)) {
            List<RunConfig> d=defaults();for(int i=0;i<d.size();i++)if(!sameParameters(candidate(d.get(i).id),d.get(i)))
                throw new IllegalArgumentException("Formal C0-C5 table is frozen; use profile=smoke for small experiments");
        } else if(!"smoke".equals(profile))throw new IllegalArgumentException("Profile must be formal or smoke");
    }
    public RunConfig candidate(String id){for(RunConfig c:candidates)if(c.id.equals(id))return c;throw new IllegalArgumentException("Unknown config "+id);}
    public static boolean sameParameters(RunConfig a,RunConfig b){return a.populationSize==b.populationSize && a.generations==b.generations && a.crossoverProbability==b.crossoverProbability && a.crossP==b.crossP;}
    private void checkSeeds(List<Long> seeds){if(seeds==null || seeds.isEmpty() || seeds.contains(null) || new HashSet<>(seeds).size()!=seeds.size())throw new IllegalArgumentException("Seed list must be nonempty and unique");}
}

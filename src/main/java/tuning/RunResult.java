package tuning;
import java.util.*;
public class RunResult {
    public String status="success";
    public String algorithmVersion=SearchRunner.VERSION;
    public String metricVersion=LegacyAblationProtocol.metricVersion();
    public String inputHash;
    public String taskKey;
    public RunConfig config;
    public Map<String,Object> guidance;
    public double guidanceMillis;
    public long guidanceEvaluations;
    public long evaluations;
    public long unusedBudget;
    public int completedGenerations;
    public double searchMillis;
    public double monitoringMillis;
    public double controlMillis;
    public double auditMillis;
    public double searchComputeMillis;
    public Map<String,Object> onlineControl;
    public double inputMillis;
    public double evaluationExportMillis;
    public double workerMillis;
    public int validUniqueCount;
    public int nonDominatedCount;
    public int invalidCount;
    public int populationSize;
    public double validRate;
    public List<SolutionRecord> population=new ArrayList<>();
    public List<SolutionRecord> front=new ArrayList<>();
    public Map<String,Object> structure=new LinkedHashMap<>();
    public List<String> warnings=new ArrayList<>();
    public static class SolutionRecord {
        public int[] chromosome;
        /** Maximization order: -IFN, CHD, CMQ, Instability, UCM. */
        public double[] objectives;
        /** Display order: IFN, CHD, CMQ, Instability, UCM. */
        public double[] metrics;
        public int serviceCount;
        public boolean valid=true;
        public List<Map<String,Object>> services=new ArrayList<>();
    }
}

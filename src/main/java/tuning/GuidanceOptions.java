package tuning;
import java.util.*;
/** Independent candidate generator; no credentials are serialized. */
public final class GuidanceOptions {
    public String version="small-model-batch-guidance-v1";
    public String mode="off"; // off, numeric, jev, replay
    public int intervalGenerations=10, maxTasks=4, candidatesPerTask=2, maxSelected=3;
    public double lossTolerance=.5;
    public Generator generator=new Generator();
    public String replayFile;
    public static class Generator {
        public String baseUrl="https://api.chatfire.cn/v1";
        public String model="gpt-4.1-mini-2025-04-14";
        public String apiKeyEnv="CHATFIRE_API_KEY";
        public double temperature=.3;
        public int timeoutMillis=60000;
    }
    public boolean enabled(){return !"off".equals(mode);}
    public void validate(){
        if(!Arrays.asList("off","numeric","jev","replay").contains(mode) || intervalGenerations!=10 || maxTasks<1 || maxTasks>4 ||
           candidatesPerTask<1 || candidatesPerTask>2 || maxSelected<1 || !Double.isFinite(lossTolerance) || lossTolerance<0 ||
           generator==null || generator.timeoutMillis<1 || generator.model==null || generator.model.contains("latest") ||
           generator.apiKeyEnv==null || !generator.apiKeyEnv.matches("[A-Z_][A-Z0-9_]*") ||
           !Double.isFinite(generator.temperature) || generator.temperature<0 || generator.temperature>2)
            throw new IllegalArgumentException("Invalid guidance options");
        if("replay".equals(mode) && replayFile==null)throw new IllegalArgumentException("Guidance replayFile required");
    }
}

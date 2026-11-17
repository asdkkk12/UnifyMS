package tuning;
import com.alibaba.fastjson2.JSONObject;
import java.nio.file.Path;
public interface ConfigurationSelector {
    JSONObject select(JSONObject evidence, Path auditFile) throws Exception;
}

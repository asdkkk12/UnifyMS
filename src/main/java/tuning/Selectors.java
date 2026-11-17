package tuning;
import com.alibaba.fastjson2.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class Selectors {
    public static String policyVersion(String selector) {
        if("jev".equals(selector))return SelectionEvidence.VERSION;
        if("numeric".equals(selector))return "max-mean-hv-v1";
        if("mock".equals(selector))return "fixed-c0-v1";
        throw new IllegalArgumentException("Unknown selector: "+selector);
    }
    public static class MockJevSelector implements ConfigurationSelector {
        public JSONObject select(JSONObject evidence,Path audit)throws Exception {
            JSONObject r=new JSONObject();r.put("status","success");r.put("selector","mock");r.put("selectionPolicyVersion",policyVersion("mock"));r.put("mock",true);r.put("choice","C0");
            r.put("totalTokens",0);r.put("totalResponseMillis",0);FilesIO.write(audit,r);return r;
        }
    }
    public static class NumericSelector implements ConfigurationSelector {
        public JSONObject select(JSONObject evidence,Path audit)throws Exception {
            String best=null;double max=Double.NEGATIVE_INFINITY;
            JSONObject configs=evidence.getJSONObject("candidates");List<String> ids=new ArrayList<>(configs.keySet());Collections.sort(ids);
            for(String id:ids){double value=configs.getJSONObject(id).getDoubleValue("meanHv");if(value>max){max=value;best=id;}}
            if(best==null)throw new IllegalArgumentException("No calibration evidence");
            JSONObject r=new JSONObject();r.put("status","success");r.put("selector","numeric");r.put("selectionPolicyVersion",policyVersion("numeric"));r.put("choice",best);r.put("meanHv",max);
            r.put("totalTokens",0);r.put("totalResponseMillis",0);FilesIO.write(audit,r);return r;
        }
    }
    public static class JevSelector implements ConfigurationSelector {
        private final ExperimentConfig.JevOptions options;
        private final String key;
        public JevSelector(ExperimentConfig.JevOptions options,String key){this.options=options;this.key=key;}
        public JSONObject select(JSONObject evidence,Path audit)throws Exception {
            JSONObject record=new JSONObject();record.put("selector","jev");record.put("selectionPolicyVersion",SelectionEvidence.VERSION);record.put("mock",false);record.put("status","failed");
            JSONArray attempts=new JSONArray();record.put("attempts",attempts);
            record.put("requestedModel",options.model);record.put("totalTokens",null);record.put("totalResponseMillis",0);
            if(key==null || key.trim().isEmpty() || options.model==null || options.model.trim().isEmpty() || options.model.contains("latest")) {
                record.put("error","Set TYPESAFE_API_KEY and a fixed model version");FilesIO.write(audit,record);throw new IOException("Missing key or fixed model version");
            }
            URL url=new URL(options.endpoint);
            if(!"https".equals(url.getProtocol()) && !("http".equals(url.getProtocol()) && Arrays.asList("localhost","127.0.0.1","::1").contains(url.getHost())))
                throw new IllegalArgumentException("JEV endpoint must use HTTPS (HTTP only for localhost tests)");
            JSONObject request=SelectionEvidence.request(evidence,options.model);
            JSONObject state=SelectionEvidence.build(evidence);
            List<String> ids=new ArrayList<>(state.getJSONObject("candidates").keySet());Collections.sort(ids);
            record.put("request",request);long knownTokens=0;boolean allUsage=true;double totalMillis=0;
            for(int retry=0;retry<2;retry++) {
                JSONObject attempt=new JSONObject();attempts.add(attempt);HttpURLConnection connection=null;long start=System.nanoTime();
                JSONObject response=null;
                try {
                    connection=openConnection(url);connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(options.timeoutMillis);connection.setReadTimeout(options.timeoutMillis);
                    connection.setRequestMethod("POST");connection.setRequestProperty("Authorization","Bearer "+key);
                    connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);
                    try(OutputStream out=connection.getOutputStream()){out.write(request.toJSONString().getBytes(StandardCharsets.UTF_8));}
                    int code=connection.getResponseCode();attempt.put("httpStatus",code);
                    InputStream input=code>=400?connection.getErrorStream():connection.getInputStream();
                    String body=read(input);attempt.put("rawResponse",body);
                    response=JSON.parseObject(body);attempt.put("response",response);
                    if(code<200 || code>=300)throw new IOException("JEV HTTP "+code);
                    if(response==null || response.getString("model")==null)throw new IOException("Missing response model");
                    JSONObject answer=response.getJSONObject("answers").getJSONObject("configuration");
                    if(!"choice".equals(answer.getString("type")) || !ids.contains(answer.getString("choice")))throw new IOException("Invalid JEV Choice response");
                    record.put("choice",answer.getString("choice"));record.put("actualModel",response.getString("model"));
                    record.put("confidence",answer.get("confidence"));record.put("probabilities",answer.get("probabilities"));
                    record.put("status","success");
                }catch(Exception e){attempt.put("error",e.getClass().getSimpleName()+": "+safe(e.getMessage()));}
                finally {
                    double elapsed=(System.nanoTime()-start)/1e6;totalMillis+=elapsed;attempt.put("responseMillis",elapsed);
                    JSONObject usage=response==null?null:response.getJSONObject("usage");
                    Long tokens=null;
                    if(usage!=null){
                        if(usage.containsKey("total_tokens"))tokens=usage.getLong("total_tokens");
                        else if(usage.containsKey("input_tokens")&&usage.containsKey("output_tokens"))tokens=usage.getLongValue("input_tokens")+usage.getLongValue("output_tokens");
                    }
                    if(tokens==null)allUsage=false;else knownTokens+=tokens;
                    record.put("knownTokens",knownTokens);record.put("usageComplete",allUsage);
                    record.put("totalTokens",allUsage?knownTokens:null);record.put("totalResponseMillis",totalMillis);
                    if(connection!=null)connection.disconnect();FilesIO.write(audit,record);
                }
                if("success".equals(record.getString("status"))) {
                    record.put("programExplanation",SelectionEvidence.explanation(state,record.getString("choice")));
                    FilesIO.write(audit,record);
                    FilesIO.write(audit.resolveSibling(audit.getFileName()+".evidence.json"),state);
                    SelectionEvidence.report(audit.resolveSibling(audit.getFileName()+".analysis.md"),state,record);
                    return record;
                }
            }
            throw new IOException("JEV selection failed; see "+audit);
        }
        protected HttpURLConnection openConnection(URL url)throws IOException { return (HttpURLConnection)url.openConnection(); }
        private String safe(String text){return text==null?"":text.replace(key,"[redacted]");}
        private static String read(InputStream input)throws IOException {
            if(input==null)return "";try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>10000000)throw new IOException("Response too large");}
                return new String(out.toByteArray(),StandardCharsets.UTF_8);}
        }
    }
}

package tuning;

import com.alibaba.fastjson2.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded Choice request. No retry and no credentials in the audit record. */
public class OnlineJevClient implements OnlineControlSession.DecisionClient {
    public static final String PROMPT_VERSION="novelty-gain-history-choice-v2";
    public static final String INSTRUCTIONS=
        "Choose INCREASE, KEEP or DECREASE for the next search window. " +
        "Novelty is the fraction of unique offspring partitions absent from the current parent population. " +
        "Gain = HV(ND(P union Q)) - HV(ND(P)), before truncation, using frozen normalization. " +
        "Gain is nonnegative marginal coverage, not a degradation rate or the final retained population HV increase. " +
        "Use state.gainThreshold as the meaningful per-generation window-mean Gain threshold: " +
        "meanGain <= gainThreshold is low return, even when strictly positive. " +
        "A positive Gain alone is not sufficient reason to KEEP. A meanGain above the threshold " +
        "is evidence of meaningful coverage, not proof that the current probability is optimal. " +
        "Use state.noveltyThreshold to assess novelty. Reference heuristic: low novelty and low return " +
        "suggests INCREASE; high novelty and low return suggests DECREASE; sustained meaningful return " +
        "can support KEEP. These are guidance, NOT mandatory mappings; do not change probability merely " +
        "to avoid KEEP, and do not assume increasing or decreasing probability will improve final HV. " +
        "Consider the recent windows, previous actions and subsequent observations; distinguish sustained trends " +
        "from one-window fluctuations. Changes after an action do not establish causality. " +
        "Only trigger probability changes; internal crossP stays fixed. Do not invent measurements or future outcomes. " +
        "Return exactly one offered Choice. Confidence is not evidence of improved decomposition quality.";
    private final ExperimentConfig.JevOptions jev;
    private final int timeout;
    private final String key;
    public OnlineJevClient(ExperimentConfig.JevOptions jev,int timeout,String key){this.jev=jev;this.timeout=timeout;this.key=key;}
    public static JSONObject request(JSONObject evidence,String model) {
        JSONObject question=new JSONObject();question.put("type","choice");question.put("instructions",LegacyAblationProtocol.onlineInstructions());
        question.put("criteria",evidence.get("actions"));
        JSONObject questions=new JSONObject();questions.put("configuration",question);
        JSONObject request=new JSONObject();request.put("model",model);request.put("state",evidence);request.put("questions",questions);return request;
    }
    @Override public JSONObject decide(JSONObject evidence) {
        JSONObject audit=new JSONObject();audit.put("status","failed");audit.put("requestedModel",jev.model);
        audit.put("promptVersion",PROMPT_VERSION);audit.put("totalTokens",null);audit.put("usageComplete",false);audit.put("attempted",false);
        JSONObject request=request(evidence,jev.model);audit.put("request",request);
        long start=System.nanoTime();AtomicReference<HttpURLConnection> active=new AtomicReference<>();
        ExecutorService executor=null;
        try {
            if(key==null || key.trim().isEmpty())throw new IOException("Missing runtime TYPESAFE_API_KEY");
            if(jev.model==null || jev.model.trim().isEmpty() || jev.model.toLowerCase(Locale.ROOT).contains("latest"))
                throw new IOException("A fixed model version is required");
            URL url=new URL(jev.endpoint);
            boolean local=Arrays.asList("localhost","127.0.0.1","::1","[::1]").contains(url.getHost());
            if(url.getUserInfo()!=null || !("https".equals(url.getProtocol()) || (local && "http".equals(url.getProtocol()))))
                throw new IOException("JEV requires HTTPS (HTTP only for local tests), without URL credentials");
            executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"online-jev-request");t.setDaemon(true);return t;});
            audit.put("attempted",true);
            Future<JSONObject> future=executor.submit(()->exchange(url,request,active));
            JSONObject transport=future.get(timeout,TimeUnit.MILLISECONDS);audit.putAll(transport);
            JSONObject response=audit.getJSONObject("response");
            if(response!=null) {
                JSONObject usage=response.getJSONObject("usage");Long tokens=null;
                if(usage!=null) {
                    if(usage.containsKey("total_tokens"))tokens=usage.getLong("total_tokens");
                    else if(usage.containsKey("input_tokens") && usage.containsKey("output_tokens"))
                        tokens=usage.getLongValue("input_tokens")+usage.getLongValue("output_tokens");
                }
                if(tokens!=null && tokens>=0){audit.put("totalTokens",tokens);audit.put("usageComplete",true);}
            }
            int status=audit.getIntValue("httpStatus");
            if(status<200 || status>=300)throw new IOException("JEV HTTP "+status);
            if(response==null || response.getString("model")==null || response.getString("model").trim().isEmpty())
                throw new IOException("Missing response model");
            audit.put("actualModel",response.getString("model"));
            JSONObject answers=response.getJSONObject("answers");
            JSONObject answer=answers==null?null:answers.getJSONObject("configuration");
            if(answer==null || !"choice".equals(answer.getString("type")) || !OnlineControlSession.ACTIONS.contains(answer.getString("choice")))
                throw new IOException("Invalid online Choice response");
            audit.put("choice",answer.getString("choice"));audit.put("confidence",answer.get("confidence"));
            audit.put("probabilities",answer.get("probabilities"));audit.put("status","success");
        } catch(Exception error) {
            Throwable cause=error instanceof ExecutionException && error.getCause()!=null?error.getCause():error;
            audit.put("error",redact(cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage())));
            audit.remove("choice");
        } finally {
            if(executor!=null)executor.shutdownNow();
            HttpURLConnection connection=active.get();
            if(connection!=null)connection.disconnect();
            audit.put("responseMillis",(System.nanoTime()-start)/1e6);
        }
        return audit;
    }
    protected HttpURLConnection openConnection(URL url)throws IOException{return (HttpURLConnection)url.openConnection();}
    private JSONObject exchange(URL url,JSONObject request,AtomicReference<HttpURLConnection> active)throws Exception {
        HttpURLConnection c=openConnection(url);active.set(c);
        try {
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(timeout);c.setReadTimeout(timeout);
            c.setRequestMethod("POST");c.setRequestProperty("Authorization","Bearer "+key);
            c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);
            try(OutputStream out=c.getOutputStream()){out.write(request.toJSONString().getBytes(StandardCharsets.UTF_8));}
            int status=c.getResponseCode();JSONObject result=new JSONObject();result.put("httpStatus",status);
            InputStream input=status>=400?c.getErrorStream():c.getInputStream();
            String raw="";
            if(input!=null)try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>2_000_000)throw new IOException("Response too large");}
                raw=redact(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
            result.put("rawResponse",raw);
            try{result.put("response",JSON.parseObject(raw));}catch(RuntimeException bad){result.put("parseError","Response is not a JSON object");}
            return result;
        } finally {c.disconnect();}
    }
    private String redact(String value){return key==null || key.isEmpty()?value:value.replace(key,"[redacted]");}
}

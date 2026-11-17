package tuning;
import com.alibaba.fastjson2.*;
import java.util.*;
public final class Statistics {
    public static JSONObject describe(List<Double> values) {
        JSONObject out=new JSONObject();out.put("samples",values.size());
        if(values.isEmpty()){out.put("mean",null);out.put("sampleStandardDeviation",null);return out;}
        double mean=0;for(double x:values)mean+=x;mean/=values.size();double squared=0;for(double x:values)squared+=(x-mean)*(x-mean);
        out.put("mean",mean);out.put("sampleStandardDeviation",values.size()<2?null:Math.sqrt(squared/(values.size()-1)));return out;
    }
    public static JSONObject paired(List<Double> differences) {
        JSONObject out=new JSONObject();out.put("pairs",differences.size());
        if(differences.isEmpty()){out.put("meanDifference",null);out.put("ci95",null);return out;}
        double mean=0;for(double x:differences)mean+=x;mean/=differences.size();out.put("meanDifference",mean);
        if(differences.size()==1){out.put("ci95",null);out.put("note","One pair cannot estimate a confidence interval");return out;}
        Random random=new Random(20261031L);double[] samples=new double[10000];
        for(int b=0;b<samples.length;b++){double s=0;for(int i=0;i<differences.size();i++)s+=differences.get(random.nextInt(differences.size()));samples[b]=s/differences.size();}
        Arrays.sort(samples);out.put("ci95",new double[]{samples[249],samples[9749]});out.put("bootstrapSeed",20261031L);out.put("bootstrapSamples",10000);return out;
    }
}

package tuning;

import org.moeaframework.core.Solution;
import org.moeaframework.core.indicator.Hypervolume;
import java.util.*;
import tuning.RunResult.SolutionRecord;

public final class Quality {
    private Quality() {}
    public static boolean dominates(double[] a,double[] b) {
        boolean strict=false;
        for(int i=0;i<5;i++){if(a[i]<b[i])return false;if(a[i]>b[i])strict=true;}
        return strict;
    }
    public static List<SolutionRecord> front(List<SolutionRecord> rows) {
        List<SolutionRecord> out=new ArrayList<>();
        for(SolutionRecord a:rows){boolean dominated=false;for(SolutionRecord b:rows)
            if(a!=b && dominates(b.objectives,a.objectives)){dominated=true;break;}
            if(!dominated)out.add(a);}
        out.sort((a,b)->compare(a.chromosome,b.chromosome)); return out;
    }
    private static int compare(int[] a,int[] b) {
        for(int i=0;i<Math.min(a.length,b.length);i++){int d=Integer.compare(a[i],b[i]);if(d!=0)return d;}
        return Integer.compare(a.length,b.length);
    }
    public static class Normalization {
        public double[] min=new double[5];
        public double[] max=new double[5];
        public List<Boolean> zeroSpan=new ArrayList<>(Arrays.asList(false,false,false,false,false));
        public double[] reference={1.1,1.1,1.1,1.1,1.1};
        public double[] normalize(double[] objectives) {
            double[] z=new double[5];for(int i=0;i<5;i++)z[i]=(-objectives[i]-min[i])/(zeroSpan.get(i)?1:max[i]-min[i]);return z;
        }
    }
    public static Normalization fit(List<RunResult> runs) {
        Normalization n=new Normalization();Arrays.fill(n.min,Double.POSITIVE_INFINITY);Arrays.fill(n.max,Double.NEGATIVE_INFINITY);
        for(RunResult r:runs) for(SolutionRecord s:r.population) for(int j=0;j<5;j++){
            n.min[j]=Math.min(n.min[j],-s.objectives[j]);n.max[j]=Math.max(n.max[j],-s.objectives[j]);}
        for(int j=0;j<5;j++){if(!Double.isFinite(n.min[j]))throw new IllegalArgumentException("No valid calibration points");n.zeroSpan.set(j,n.max[j]==n.min[j]);}
        return n;
    }
    public static double hv(List<SolutionRecord> front,Normalization n) {
        List<Solution> boxes=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(SolutionRecord r:front){double[] z=n.normalize(r.objectives),box=new double[5];boolean contributes=true;
            for(int j=0;j<5;j++){box[j]=n.reference[j]-z[j];if(box[j]<=0)contributes=false;}
            if(contributes && seen.add(Arrays.toString(box))){Solution s=new Solution(0,5);s.setObjectives(box);boxes.add(s);}}
        return boxes.isEmpty()?0:Hypervolume.calculateHypervolume(boxes,boxes.size(),5);
    }
    public static SolutionRecord representative(List<SolutionRecord> front,Normalization n) {
        SolutionRecord best=null;double distance=Double.POSITIVE_INFINITY;
        for(SolutionRecord s:front){double d=0;for(double z:n.normalize(s.objectives))d+=z*z;
            if(best==null || d<distance || (d==distance && compare(s.chromosome,best.chromosome)<0)){best=s;distance=d;}}
        return best;
    }
    public static Map<String,Object> evaluate(RunResult r,Normalization n) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("hv",hv(r.front,n));out.put("representative",representative(r.front,n));
        out.put("validUniqueCount",r.validUniqueCount);out.put("nonDominatedCount",r.nonDominatedCount);out.put("validRate",r.validRate);return out;
    }
}

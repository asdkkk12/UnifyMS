package Evaluation;

import GA.DiagramParser;
import java.util.*;

/** IEEE manuscript, section V-A, equations (5)-(21). Raw values before library rounding. */
public final class PaperMetrics {
    public static final String VERSION="paper-ieee-eq5-21-v2-isolated-zero";
    private static Context context;
    private PaperMetrics() {}

    public static void initialize(Map<String,? extends Set<String>> attributes,Set<String> scenarioPairs) {
        context=new Context(attributes,scenarioPairs);
    }
    public static String pair(String a,String b){return a.compareTo(b)<0?a+"\u0000"+b:b+"\u0000"+a;}
    public static double jaccard(Set<String> a,Set<String> b) {
        int common=0;for(String item:a)if(b.contains(item))common++;
        int union=a.size()+b.size()-common;return union==0?0:(double)common/union;
    }
    private static Context current() {
        if(context==null || !context.matches()) {
            try {tuning.MetricMetadata.loadCurrent();}
            catch(Exception e){throw new IllegalStateException("Paper metrics require entity attributes and explicit UC relations for the current model",e);}
        }
        if(context==null || !context.matches())throw new IllegalStateException("Paper metric context was not initialized");
        return context;
    }
    /** [IFN, CHD, CMQ, Instability, UCM, Intra_U, Inter_U]. Caller cannot mutate cache. */
    public static double[] evaluate(ArrayList<ArrayList<Integer>> useCases,ArrayList<ArrayList<Integer>> entities) {
        return current().evaluate(useCases,entities).clone();
    }
    public static double[] useCaseMeans(ArrayList<ArrayList<Integer>> groups) {
        Context c=current();int[] assignment=c.assign(groups,c.uCount,true);
        double[] result=c.means(c.ucSimilarity,assignment);return result;
    }
    public static double operationSimilarity(String a,String b){Context c=current();return c.operationSimilarity[c.operationId.get(a)][c.operationId.get(b)];}
    public static double entitySimilarity(int a,int b){return current().entitySimilarity[a][b];}
    public static double useCaseSimilarity(int a,int b){return current().ucSimilarity[a][b];}

    private static final class Context {
        final boolean legacy=tuning.LegacyAblationProtocol.enabled();
        final String metricVersion=tuning.LegacyAblationProtocol.metricVersion();
        final Object ucMatrix=DiagramParser.instancegetUCtoE, entityMatrix=DiagramParser.instancegetEtoE,
            ucOps=DiagramParser.instancegetUCtoOp, opEntities=DiagramParser.instancegetOPstoEntity,
            actors=DiagramParser.instancegetActortoUC, entityNames=DiagramParser.instancegetPostoEntity,
            ucNames=DiagramParser.instancegetPostoUC;
        final int uCount=DiagramParser.numofUC,eCount=DiagramParser.numofEntity;
        final Map<String,Integer> operationId=new TreeMap<>();
        final int[][] operations;
        final double[][] operationSimilarity,entitySimilarity,ucSimilarity;
        final Map<String,double[]> cache=new LinkedHashMap<String,double[]>(128,.75f,true){
            protected boolean removeEldestEntry(Map.Entry<String,double[]> row){return size()>8192;}
        };
        boolean matches(){return metricVersion.equals(tuning.LegacyAblationProtocol.metricVersion()) && legacy==tuning.LegacyAblationProtocol.enabled() && uCount==DiagramParser.numofUC && eCount==DiagramParser.numofEntity &&
            ucMatrix==DiagramParser.instancegetUCtoE && entityMatrix==DiagramParser.instancegetEtoE &&
            ucOps==DiagramParser.instancegetUCtoOp && opEntities==DiagramParser.instancegetOPstoEntity &&
            actors==DiagramParser.instancegetActortoUC && entityNames==DiagramParser.instancegetPostoEntity && ucNames==DiagramParser.instancegetPostoUC;}
        Context(Map<String,? extends Set<String>> attributes,Set<String> relations) {
            if(attributes==null || relations==null)throw new IllegalArgumentException("Metric metadata is missing");
            Set<String> names=new TreeSet<>();
            for(int i=0;i<uCount;i++)names.addAll(DiagramParser.instancegetUCtoOp.getOrDefault(DiagramParser.instancegetPostoUC.get(i),new HashSet<>()));
            for(String name:names)operationId.put(name,operationId.size());
            operations=new int[uCount][];
            for(int i=0;i<uCount;i++) {
                Set<String> ops=DiagramParser.instancegetUCtoOp.getOrDefault(DiagramParser.instancegetPostoUC.get(i),new HashSet<>());
                operations[i]=ops.stream().mapToInt(operationId::get).sorted().toArray();
            }
            operationSimilarity=new double[names.size()][names.size()];
            for(String a:names)for(String b:names)operationSimilarity[operationId.get(a)][operationId.get(b)]=jaccard(
                DiagramParser.instancegetOPstoEntity.getOrDefault(a,new HashSet<>()),DiagramParser.instancegetOPstoEntity.getOrDefault(b,new HashSet<>()));
            entitySimilarity=new double[eCount][eCount];
            for(int i=0;i<eCount;i++) {
                String a=DiagramParser.instancegetPostoEntity.get(i);
                if(!attributes.containsKey(a))throw new IllegalArgumentException("Missing declared attributes for entity "+a);
                for(int j=0;j<eCount;j++) {
                    String b=DiagramParser.instancegetPostoEntity.get(j);
                    if(!attributes.containsKey(b))throw new IllegalArgumentException("Missing declared attributes for entity "+b);
                    entitySimilarity[i][j]=jaccard(attributes.get(a),attributes.get(b));
                }
            }
            ucSimilarity=new double[uCount][uCount];
            for(int i=0;i<uCount;i++)for(int j=i+1;j<uCount;j++) {
                String a=DiagramParser.instancegetPostoUC.get(i),b=DiagramParser.instancegetPostoUC.get(j);
                boolean sharedActor=false;
                for(Set<String> associations:DiagramParser.instancegetActortoUC.values())if(associations.contains(a)&&associations.contains(b)){sharedActor=true;break;}
                double dot=0,na=0,nb=0;
                for(int k=0;k<eCount;k++) {
                    boolean x=DiagramParser.instancegetUCtoE.get(i)[k]!=0,y=DiagramParser.instancegetUCtoE.get(j)[k]!=0;
                    if(x)na++;if(y)nb++;if(x&&y)dot++;
                }
                double cosine=na==0||nb==0?0:dot/Math.sqrt(na*nb);
                double value=.2*(sharedActor?1:0)+.3*(relations.contains(pair(a,b))?1:0)+.5*cosine;
                ucSimilarity[i][j]=value;ucSimilarity[j][i]=value;
            }
        }
        int[] assign(ArrayList<ArrayList<Integer>> groups,int count,boolean requireNonempty) {
            int[] assignment=new int[count];Arrays.fill(assignment,-1);
            if(groups.isEmpty())throw new IllegalArgumentException("No services");
            for(int s=0;s<groups.size();s++) {
                if(requireNonempty && groups.get(s).isEmpty())throw new IllegalArgumentException("A service must contain a use case");
                for(int i:groups.get(s)) {
                    if(i<0||i>=count||assignment[i]!=-1)throw new IllegalArgumentException("Invalid or duplicate partition element "+i);
                    assignment[i]=s;
                }
            }
            for(int x:assignment)if(x<0)throw new IllegalArgumentException("Partition omits an element");
            return assignment;
        }
        double[] means(double[][] similarity,int[] assignment) {
            double in=0,out=0;long ni=0,no=0;
            // Distinct unordered pairs; ordered pairs would double numerator and denominator equally.
            for(int i=0;i<assignment.length;i++)for(int j=i+1;j<assignment.length;j++)
                if(assignment[i]==assignment[j]){in+=similarity[i][j];ni++;}else{out+=similarity[i][j];no++;}
            return new double[]{ni==0?0:in/ni,no==0?0:out/no};
        }
        double[] evaluate(ArrayList<ArrayList<Integer>> uc,ArrayList<ArrayList<Integer>> en) {
            if(uc.size()!=en.size())throw new IllegalArgumentException("Use-case/entity service counts differ");
            int[] ua=assign(uc,uCount,true),ea=assign(en,eCount,false);
            String key=Arrays.toString(ua)+"|"+Arrays.toString(ea);double[] cached=cache.get(key);if(cached!=null)return cached;
            int[][][] interfaces=QualityCalculate.generateInterface(uc,en);
            double ports=0,instability=0,chd=0;
            for(int s=0;s<uc.size();s++) {
                int required=0,provided=0;
                for(int e=0;e<eCount;e++){required+=interfaces[0][s][e];provided+=interfaces[1][s][e];}
                ports+=required+provided;
                instability+=required+provided==0?tuning.LegacyAblationProtocol.isolatedScore():(double)provided/(required+provided);
                BitSet ops=new BitSet();for(int u:uc.get(s))for(int o:operations[u])ops.set(o);
                int n=ops.cardinality();double sum=0;
                for(int i=ops.nextSetBit(0);i>=0;i=ops.nextSetBit(i+1))
                    for(int j=ops.nextSetBit(i+1);j>=0;j=ops.nextSetBit(j+1))sum+=2*operationSimilarity[i][j];
                chd+=n==0?0:sum/((double)n*n);
            }
            double[] e=means(entitySimilarity,ea),u=means(ucSimilarity,ua);
            double[] result={ports/uc.size(),chd/uc.size(),e[0]-e[1],instability/uc.size(),u[0]-u[1],u[0],u[1]};
            cache.put(key,result);return result;
        }
    }
}

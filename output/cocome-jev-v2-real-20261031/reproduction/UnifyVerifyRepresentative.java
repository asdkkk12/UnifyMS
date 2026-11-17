import tuning.*;
import GA.*;
import com.alibaba.fastjson2.*;
import com.debacharya.nsgaii.datastructure.*;
import com.debacharya.nsgaii.objectivefunction.*;
import java.nio.file.*;
import java.util.*;
public class UnifyVerifyRepresentative {
 public static void main(String[] args)throws Exception {
  ModelLoader.load(Paths.get(args[0]));JSONObject run=FilesIO.object(Paths.get(args[1]));
  int[] genes=run.getJSONObject("representative").getObject("chromosome",int[].class);
  List<IntegerAllele> alleles=new ArrayList<>();for(int gene:genes)alleles.add(new IntegerAllele(gene));
  Chromosome chromosome=new Chromosome(alleles);List<AbstractObjectiveFunction> objectives=SearchRunner.objectives();
  double[] raw=new double[5],metrics=new double[5];for(int i=0;i<5;i++){raw[i]=objectives.get(i).getValue(chromosome);metrics[i]=(i==0?-1:1)*raw[i];}
  Map<String,Object> result=new LinkedHashMap<>();result.put("rawObjectives",raw);result.put("rawMetrics",metrics);
  result.put("searchObjectives",run.getJSONObject("representative").get("objectives"));
  result.put("inputHash",FilesIO.inputHash(Paths.get(args[0])));result.put("taskKey",run.getJSONObject("run").get("taskKey"));
  result.put("note","Direct objective getValue recomputation. Legacy Chromosome.addObjectiveValue rounds search vectors to four decimal places; run HV uses those search vectors.");
  FilesIO.write(Paths.get(args[2]),result);
 }
}

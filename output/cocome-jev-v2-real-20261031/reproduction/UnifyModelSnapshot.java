import tuning.*;
import GA.*;
import java.nio.file.*;
import java.util.*;
public class UnifyModelSnapshot {
 public static void main(String[] args)throws Exception {
  ModelLoader.load(Paths.get(args[0]));Map<String,Object> m=new LinkedHashMap<>();
  m.put("inputHash",FilesIO.inputHash(Paths.get(args[0])));m.put("structure",ModelLoader.structure());
  m.put("actors",DiagramParser.instancegetActortoUC);m.put("useCaseOperations",DiagramParser.instancegetUCtoOp);
  m.put("operationEntities",DiagramParser.instancegetOPstoEntity);Map<String,String> en=new TreeMap<>();for(Map.Entry<Integer,String> e:DiagramParser.instancegetPostoEntity.entrySet())en.put(e.getKey().toString(),e.getValue());m.put("entityNames",en);
  Map<String,String> uc=new TreeMap<>();for(Map.Entry<Integer,String> e:DiagramParser.instancegetPostoUC.entrySet())uc.put(e.getKey().toString(),e.getValue());m.put("useCaseNames",uc);m.put("useCaseEntityMatrix",DiagramParser.instancegetUCtoE);
  m.put("entityRelationMatrix",DiagramParser.instancegetEtoE);m.put("useCaseSimilarityMatrix",DiagramParser.DataSet);
  FilesIO.write(Paths.get(args[1]),m);
 }
}

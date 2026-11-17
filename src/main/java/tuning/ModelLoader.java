package tuning;
import GA.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public final class ModelLoader {
    public static List<String> load(Path input)throws Exception {
        List<String> warnings=new ArrayList<>();
        if(Files.isDirectory(input)) {
            List<String> classes=new ArrayList<>();for(Path p:FilesIO.javaFiles(input))classes.add(p.toString());
            if(classes.isEmpty())throw new IllegalArgumentException("No Java input");
            DiagramParser.resetnameofRemodel(input.getFileName().toString(),classes,input.toString());
            warnings.add("Source model uses the legacy controller/service/entity heuristics; inspect extracted structure before formal experiments");
        } else {
            if(!input.toString().endsWith(".remodel"))throw new IllegalArgumentException("Expected .remodel file or Java source directory");
            // Preflight undeclared actors' UC references before the legacy parser unboxes null IDs.
            Map<String,Integer> uc=remodelparse.getUCPos(input.toString());
            for(Map.Entry<String,HashSet<String>> actor:remodelparse.getActortoUC(input.toString()).entrySet())
                for(String name:actor.getValue())if(!uc.containsKey(name))
                    throw new IllegalArgumentException("Actor "+actor.getKey()+" references undeclared use case "+name);
            remodelparse.setnameofRemodel(input.toString());
            for(Field source:remodelparse.class.getFields()) {
                try {Field dest=DiagramParser.class.getField(source.getName());if(Modifier.isStatic(dest.getModifiers()))dest.set(null,source.get(null));}
                catch(NoSuchFieldException ignored) { /* Parser-specific field. */ }
            }
        }
        if(DiagramParser.numofUC<1 || DiagramParser.numofEntity<1)throw new IllegalArgumentException("Input must define use cases and entities");
        if(DiagramParser.instancegetUCtoE.size()!=DiagramParser.numofUC || DiagramParser.instancegetEtoE.size()!=DiagramParser.numofEntity)
            throw new IllegalArgumentException("Parser matrix dimension mismatch");
        for(String name:new TreeSet<>(DiagramParser.instancegetUCPos.keySet()))
            if(!DiagramParser.instancegetUCtoOp.containsKey(name))throw new IllegalArgumentException("Missing operation mapping: "+name);
        for(Map.Entry<String,HashSet<String>> e:DiagramParser.instancegetActortoUC.entrySet())
            for(String uc:e.getValue())if(!DiagramParser.instancegetUCPos.containsKey(uc))throw new IllegalArgumentException("Unknown use case: "+uc);
        MetricMetadata.load(input);
        if(Files.isDirectory(input))warnings.add("Paper CMQ now uses AST declared non-reference attribute names; Java include/extend correlation is zero because no such relations are declared in this input representation");
        return warnings;
    }
    public static Map<String,Object> structure() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("useCases",DiagramParser.numofUC);out.put("entities",DiagramParser.numofEntity);
        out.put("actors",DiagramParser.instancegetActortoUC.size());out.put("operations",DiagramParser.instancegetOPstoEntity.size());
        List<String> uc=new ArrayList<>(),entities=new ArrayList<>();
        for(int i=0;i<DiagramParser.numofUC;i++)uc.add(DiagramParser.instancegetPostoUC.get(i));
        for(int i=0;i<DiagramParser.numofEntity;i++)entities.add(DiagramParser.instancegetPostoEntity.get(i));
        out.put("useCaseNames",uc);out.put("entityNames",entities);return out;
    }
}

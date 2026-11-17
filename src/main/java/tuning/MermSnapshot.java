package tuning;

import GA.DiagramParser;
import java.nio.file.*;
import java.util.*;

/** Read-only model extraction for offline MERM export. Does not run GA or select a solution. */
public final class MermSnapshot {
    private MermSnapshot() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: tuning.MermSnapshot input.remodel|java-directory output.json");
        Path input = Paths.get(args[0]), output = Paths.get(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists; choose a new snapshot path");
        if (!Files.isDirectory(input) && !input.toString().endsWith(".remodel")) throw new IllegalArgumentException("Expected .remodel or Java source directory");
        List<String> warnings = ModelLoader.load(input);
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("inputHash", FilesIO.inputHash(input));
        snapshot.put("structure", ModelLoader.structure());
        snapshot.put("actors", DiagramParser.instancegetActortoUC);
        snapshot.put("useCaseOperations", DiagramParser.instancegetUCtoOp);
        snapshot.put("operationEntities", DiagramParser.instancegetOPstoEntity);
        Map<String,String> entities = new TreeMap<>(), useCases = new TreeMap<>();
        for (int i = 0; i < DiagramParser.numofEntity; i++) entities.put(Integer.toString(i), DiagramParser.instancegetPostoEntity.get(i));
        for (int i = 0; i < DiagramParser.numofUC; i++) useCases.put(Integer.toString(i), DiagramParser.instancegetPostoUC.get(i));
        snapshot.put("entityNames", entities);
        snapshot.put("useCaseNames", useCases);
        snapshot.put("useCaseEntityMatrix", DiagramParser.instancegetUCtoE);
        snapshot.put("entityRelationMatrix", DiagramParser.instancegetEtoE);
        snapshot.put("warnings", warnings);
        snapshot.put("metricMetadata",MetricMetadata.snapshot());
        if (Files.isDirectory(input)) {
            snapshot.put("sourceKind","java-directory-legacy");
            snapshot.putAll(MetricMetadata.sourceMetadata());
        }
        FilesIO.write(output, snapshot);
        System.out.println(output.toAbsolutePath());
    }
}

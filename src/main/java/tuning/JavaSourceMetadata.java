package tuning;

import GA.DiagramParser;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import javax.lang.model.element.Modifier;
import javax.tools.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Java declarations for export/audit and paper CMQ attributes; retains the legacy graph. */
public final class JavaSourceMetadata {
    private JavaSourceMetadata() {}

    public static Map<String,Object> inspect(Path root) throws Exception {
        List<Path> paths = FilesIO.javaFiles(root);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required to inspect Java source metadata");
        Set<String> selected = new TreeSet<>(DiagramParser.instancegetEntityPos.keySet());
        Map<String,Object> schemas = new TreeMap<>();
        Map<String,List<String>> methodSources = new TreeMap<>(), actorSources = new TreeMap<>();
        Map<String,Integer> modules = new TreeMap<>();
        List<Map<String,Object>> controllers = new ArrayList<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            List<java.io.File> files = new ArrayList<>();
            for (Path path : paths) files.add(path.toFile());
            JavacTask task = (JavacTask) compiler.getTask(null,manager,diagnostics,Arrays.asList("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
            for (CompilationUnitTree unit : task.parse()) {
                Path path = Paths.get(unit.getSourceFile().toUri());
                String relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\','/');
                String module = relative.contains("/") ? relative.substring(0,relative.indexOf('/')) : ".";
                modules.put(module,modules.getOrDefault(module,0)+1);
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                for (Tree declaration : unit.getTypeDecls()) {
                    if (!(declaration instanceof ClassTree)) continue;
                    ClassTree type = (ClassTree) declaration;
                    String name = type.getSimpleName().toString();
                    String qualified = pkg.isEmpty() ? name : pkg+"."+name;
                    List<String> declaredMethods = new ArrayList<>();
                    List<Map<String,Object>> fields = new ArrayList<>();
                    for (Tree member : type.getMembers()) {
                        if (member instanceof MethodTree) {
                            String method = ((MethodTree) member).getName().toString();
                            if (!method.equals("<init>")) declaredMethods.add(method);
                        }
                        if (member instanceof VariableTree) {
                            VariableTree field = (VariableTree) member;
                            if (field.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
                            Map<String,Object> row = new LinkedHashMap<>();
                            row.put("name",field.getName().toString());row.put("type",field.getType().toString());
                            fields.add(row); // Initializer values are deliberately not exported.
                        }
                    }
                    if (selected.contains(name)) {
                        if (schemas.containsKey(name)) throw new IllegalArgumentException("Ambiguous Java entity simple name: "+name);
                        Map<String,Object> schema = new LinkedHashMap<>();
                        schema.put("name",name);schema.put("attributes",fields);schema.put("sourceFile",relative);
                        schema.put("qualifiedName",qualified);schema.put("javaSuperType",type.getExtendsClause()==null?null:type.getExtendsClause().toString());
                        schemas.put(name,schema);
                    }
                    if (path.toString().contains("ontroller")) {
                        List<String> legacy = new ArrayList<>(new TreeSet<>(DiagramParser.extractMethodNames(path.toString())));
                        List<String> extra = new ArrayList<>(legacy);extra.removeAll(declaredMethods);
                        String actor = path.getFileName().toString().replaceFirst("\\.java$","");
                        if (actor.endsWith("Controller")) actor=actor.substring(0,actor.length()-10);
                        else if (actor.endsWith("controller")) actor=actor.substring(0,actor.length()-10);
                        actorSources.computeIfAbsent(actor,k->new ArrayList<>()).add(relative);
                        for (String method : legacy) methodSources.computeIfAbsent(method,k->new ArrayList<>()).add(relative);
                        Map<String,Object> row = new LinkedHashMap<>();
                        row.put("sourceFile",relative);row.put("class",qualified);row.put("actor",actor);
                        row.put("declaredMethods",new TreeSet<>(declaredMethods));row.put("legacyNames",legacy);
                        row.put("nonDeclarationNames",extra);controllers.add(row);
                    }
                }
            }
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                throw new IllegalArgumentException("Java syntax error at "+diagnostic.getSource()+":"+diagnostic.getLineNumber()+" "+diagnostic.getMessage(Locale.ROOT));
        if (!schemas.keySet().equals(selected)) {
            Set<String> missing = new TreeSet<>(selected);missing.removeAll(schemas.keySet());
            throw new IllegalArgumentException("Selected entities missing from source declarations: "+missing);
        }
        Map<String,List<String>> methodCollisions = new TreeMap<>(), actorCollisions = new TreeMap<>();
        for (Map.Entry<String,List<String>> row : methodSources.entrySet()) if (row.getValue().size()>1) methodCollisions.put(row.getKey(),row.getValue());
        for (Map.Entry<String,List<String>> row : actorSources.entrySet()) if (row.getValue().size()>1) actorCollisions.put(row.getKey(),row.getValue());
        Set<String> notSelected = new TreeSet<>(DiagramParser.entities);notSelected.removeAll(selected);
        Map<String,Object> audit = new LinkedHashMap<>();
        audit.put("javaFileCount",paths.size());audit.put("moduleJavaFiles",modules);audit.put("controllers",controllers);
        audit.put("methodSources",methodSources);audit.put("methodNameCollisions",methodCollisions);
        audit.put("actorSources",actorSources);audit.put("actorNameCollisions",actorCollisions);
        audit.put("detectedEntityCandidates",new TreeSet<>(DiagramParser.entities));audit.put("unselectedEntityCandidates",notSelected);
        audit.put("semantics","Legacy controller method names and simple class names define the search graph; AST supplies declared fields for paper CMQ and export, plus diagnostic evidence. The entity relation matrix remains the legacy lexical matrix, including diagonal terms.");
        Map<String,Object> result = new LinkedHashMap<>();result.put("entitySchemas",schemas);result.put("sourceAudit",audit);
        return result;
    }
}

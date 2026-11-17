package tuning;

import GA.DiagramParser;
import Evaluation.PaperMetrics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Declared attribute names and explicit include/extend edges for paper metrics. */
public final class MetricMetadata {
    private static Map<String,Object> lastSourceMetadata=Collections.emptyMap();
    private static Map<String,Set<String>> lastAttributes=Collections.emptyMap();
    private static Set<String> lastRelations=Collections.emptySet();
    private MetricMetadata() {}
    public static void loadCurrent()throws Exception {
        String input=DiagramParser.nameofRemodel;
        if(input==null || !Files.exists(Paths.get(input)))throw new IllegalStateException("Current model has no readable metadata source");
        load(Paths.get(input));
    }
    public static void load(Path input)throws Exception {
        Map<String,Set<String>> attributes=new TreeMap<>();Set<String> relations=new TreeSet<>();
        Map<String,Object> sourceMetadata=Collections.emptyMap();
        if(Files.isDirectory(input)) {
            sourceMetadata=JavaSourceMetadata.inspect(input);
            Map<?,?> schemas=(Map<?,?>)sourceMetadata.get("entitySchemas");
            Set<String> selected=DiagramParser.instancegetEntityPos.keySet();
            for(Object entity:schemas.keySet()) {
                Set<String> names=new TreeSet<>();Map<?,?> schema=(Map<?,?>)schemas.get(entity);
                for(Object field:(List<?>)schema.get("attributes")) {
                    Map<?,?> row=(Map<?,?>)field;String type=row.get("type").toString();
                    Matcher ids=Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(type);boolean reference=false;
                    while(ids.find())if(selected.contains(ids.group()))reference=true;
                    if(!reference)names.add(row.get("name").toString());
                }
                attributes.put(entity.toString(),names);
            }
        } else {
            String text=maskNonCode(new String(Files.readAllBytes(input),StandardCharsets.UTF_8));
            Matcher entities=Pattern.compile("\\bEntity\\s+(\\w+)(?:\\s+extends\\s+\\w+)?\\s*\\{").matcher(text);
            while(entities.find()) {
                int end=entities.end(),depth=1;
                while(end<text.length()&&depth>0){char c=text.charAt(end++);if(c=='{')depth++;if(c=='}')depth--;}
                if(depth!=0)throw new IllegalArgumentException("Unclosed entity "+entities.group(1));
                String body=text.substring(entities.end(),end-1).replaceAll("@\\w+\\s*\\([^)]*\\)","");
                Set<String> names=new TreeSet<>();String mode="attributes";
                for(String raw:body.split("\\R")) {
                    String line=raw.trim();if(line.isEmpty()||line.startsWith("@"))continue;
                    if(line.equals("[Refer]")){mode="references";continue;}
                    if(line.equals("[INV]")){mode="invariants";continue;}
                    if(line.equals("[Attribute]")||line.equals("[Attributes]")){mode="attributes";continue;}
                    if(!"attributes".equals(mode))continue;
                    Matcher property=Pattern.compile("^(\\w+)\\s*:\\s*\\w+(?:\\[[\\w|\\s]+\\]|\\(\\w+\\))?\\s*[*]?\\s*[!]?\\s*(?:\\(?\\s*(?:Association|Aggregation|Composition)\\s*\\)?)?\\s*$").matcher(line);
                    if(!property.matches())throw new IllegalArgumentException("Unsupported attribute declaration in "+entities.group(1)+": "+line);
                    names.add(property.group(1));
                }
                if(attributes.put(entities.group(1),names)!=null)throw new IllegalArgumentException("Duplicate entity name "+entities.group(1));
            }
            Matcher useCases=Pattern.compile("(?m)^\\s*UC::(\\w+)\\b([^\\r\\n]*)").matcher(text);
            while(useCases.find()) {
                Matcher edges=Pattern.compile("\\b(?:include|extend)\\s+(\\w+)\\b").matcher(useCases.group(2));
                while(edges.find()) {
                    String a=useCases.group(1),b=edges.group(1);
                    if(!DiagramParser.instancegetUCPos.containsKey(a)||!DiagramParser.instancegetUCPos.containsKey(b))throw new IllegalArgumentException("Unknown use case in explicit relation "+a+" -> "+b);
                    if(!a.equals(b))relations.add(PaperMetrics.pair(a,b));
                }
            }
        }
        for(String name:DiagramParser.instancegetPostoEntity.values())if(!attributes.containsKey(name))throw new IllegalArgumentException("Legacy graph entity has no active attribute declaration: "+name+" (possibly parsed from comments); correct the source/parser rather than substituting an empty attribute set");
        PaperMetrics.initialize(attributes,relations);
        lastSourceMetadata=sourceMetadata;lastAttributes=attributes;lastRelations=relations;
    }
    public static Map<String,Object> snapshot() {
        Map<String,Object> out=new LinkedHashMap<>();out.put("metricVersion",LegacyAblationProtocol.metricVersion());
        out.put("entityAttributeNames",lastAttributes);out.put("useCaseScenarioPairs",lastRelations);
        out.put("attributeIdentity","case-sensitive declared attribute names; no types or implicit inherited fields; Java entity-typed fields are references");
        out.put("pairConvention","distinct unordered pairs, no diagonal; empty means and empty-union similarity are zero");return out;
    }
    public static Map<String,Object> sourceMetadata(){return lastSourceMetadata;}
    /** Mask comments and quoted text, preserving line boundaries and code punctuation. */
    static String maskNonCode(String text) {
        StringBuilder out=new StringBuilder(text);int n=text.length();
        for(int i=0;i<n;) {
            char c=text.charAt(i);int start=i;
            if(c=='"'||c=='\'') {
                char quote=c;i++;
                while(i<n){if(text.charAt(i)=='\\'){i=Math.min(n,i+2);continue;}if(text.charAt(i++)==quote)break;}
            } else if(c=='/'&&i+1<n&&text.charAt(i+1)=='/') {i+=2;while(i<n&&text.charAt(i)!='\n'&&text.charAt(i)!='\r')i++;}
            else if(c=='/'&&i+1<n&&text.charAt(i+1)=='*') {int close=text.indexOf("*/",i+2);if(close<0)throw new IllegalArgumentException("Unclosed comment");i=close+2;}
            else {i++;continue;}
            for(int j=start;j<i;j++)if(text.charAt(j)!='\n'&&text.charAt(j)!='\r')out.setCharAt(j,' ');
        }
        return out.toString();
    }
}

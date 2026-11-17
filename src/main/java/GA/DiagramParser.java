package GA;


import org.apache.xmlbeans.impl.xb.xmlconfig.Extensionconfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiagramParser {

    public static ArrayList<double[]> instancegetUCtoE;
    public static HashMap<String, HashSet<String>> instancegetActortoUC;
    public static HashMap<Integer,String>  instancegetPostoUC;
    public static HashMap<Integer,String> instancegetPostoEntity;
    public static HashMap<String , Integer> instancegetUCPos;
    public static HashMap<String ,HashSet<String>> instancegetUCtoOp;
    public static HashMap<String ,HashSet<String>> instancegetUCtoEntity;
    public static HashMap<String , Integer> instancegetEntityPos;
    public static ArrayList<double[]> instancegetEtoE;
    public static HashMap<String, HashSet<String>> instancegetOPstoEntity;
    public static ArrayList<double[]> DataSet;
    public static ArrayList<ArrayList<Integer>> instancegetRelatedEntity;
    public static int numofUC;
    public static int numofEntity;
    public static String filepath;
    public static List<String> classs;
    public static String Mname;
    public static String nameofRemodel;
    public static HashSet<String> entities;

    public static void resetnameofRemodel(String tempMname, List<String> classes, String path) throws IOException {
        filepath = path;
        nameofRemodel = path;
        classs = new ArrayList<>(classes);
        Collections.sort(classs);
        Mname = tempMname;
        entities = getEntities();
        instancegetUCPos = getUCPos();
        instancegetPostoUC = getPostoUC();
        instancegetActortoUC = getActortoUC();
        instancegetEntityPos = new HashMap<>();
        instancegetOPstoEntity = new HashMap<>();

        instancegetUCtoOp = getUCtoOp();
        //这部分的赋值在getOPstoEntity()中
        instancegetPostoEntity = getPostoEntity();
        instancegetUCtoEntity = getUCtoEntity();

        numofUC = instancegetPostoUC.size();
        numofEntity = instancegetEntityPos.size();
        instancegetEtoE = getEtoE(nameofRemodel);
        int en = 0;
        for (int i = 0; i <instancegetEtoE.size() ; i++) {
            for (int j = 0; j < instancegetEtoE.size(); j++) {
                if (instancegetEtoE.get(i)[j]==1.0)
                    en++;
            }
        }
        en = en*2+instancegetEntityPos.size()*4;
        instancegetRelatedEntity = getRelatedEntity();
        instancegetUCtoE = getUCtoE();
        DataSet = getdataSet();

    }
    public static ArrayList<double[]> getEtoE(String s){
        ArrayList<double[]> matrix = new ArrayList<>();

        for (int i = 0; i < numofEntity; i++) {
            double[] row = new double[numofEntity];
            for (int j = 0; j < numofEntity; j++) {
                row[j] = 0;
            }
            matrix.add(row);
        }

        for (String tempactor : classs) {
            String textPath = tempactor;
            String from = null;
            try (BufferedReader reader = new BufferedReader(new FileReader(textPath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("@Data")){
                        String[] splitedClasses = tempactor.split("[\\\\/.]");
                        if(!splitedClasses[splitedClasses.length-2].endsWith("VO")  &&  !splitedClasses[splitedClasses.length-2].endsWith("BO"))
                            from = splitedClasses[splitedClasses.length-2];
                        continue;
                    }
                    if (from != null && !instancegetEntityPos.containsKey(from)){
                        break;
                    }
                    int su7 = 0;
                    if (from != null && from.equals("RequestQuery")){
                        su7 = 1;
                    }
                    if (from != null){
                        String[] splitedWords = line.split("(\\s|;)");
                        if (splitedWords.length == 0){
                            continue;
                        }
                        for (String nowWord : splitedWords){
                            if (nowWord.length() == 0)
                                continue;
                            String lowerNowWord = nowWord.toLowerCase();
                            for (String theWordToBeCompared : instancegetEntityPos.keySet()){
                                String lowerTheWordToBeCompared = theWordToBeCompared.toLowerCase();
                                if (lowerTheWordToBeCompared.contains(lowerNowWord) || lowerNowWord.contains(lowerTheWordToBeCompared)){
                                    int posy = instancegetEntityPos.get(theWordToBeCompared);
                                    int posx = instancegetEntityPos.get(from);
                                    System.out.println(from +" "+nowWord+" "+theWordToBeCompared);
                                    matrix.get(posx)[posy] = 1;
                                }
                            }

                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (int i = 0; i < matrix.size(); i++) {
            // Preserve the legacy traversal condition while guarding its indexed read.
            for (int j = 0; j < matrix.get(i).length && j < matrix.get(i)[j]; j++) {
                String x = instancegetPostoEntity.get(i);
                x = x.toLowerCase();
                String y = instancegetPostoEntity.get(j);
                y = y.toLowerCase();
                if (x.contains(y)){
                    matrix.get(j)[i] = 1;
                }
                if (y.contains(x)){
                    matrix.get(i)[j] = 1;
                }
            }
        }

        return matrix;
    }

    public static HashMap<String , Integer> getUCPos(String s){
        HashMap<String , Integer> result = new HashMap<>();
        int cnt = 0;
        for (Map.Entry<String, HashSet<String>> entry : instancegetUCtoOp.entrySet()) {
            String uc = entry.getKey();
            result.put(uc,cnt);
        }
        return result;
    }

    public static HashMap<String , Integer> getEntityPos(){
                 HashMap<String , Integer> instancegetUCtoEntity = new HashMap<>();
            int cnt = 0;
            for (Map.Entry<String, HashSet<String>> entry : instancegetUCtoOp.entrySet()) {
                String uc = entry.getKey();
                HashSet<String> ops = entry.getValue();
                HashSet<String> entities = new HashSet<>();

                for (String op : ops) {
                    if (instancegetOPstoEntity.containsKey(op)) {
                        for(String entity : instancegetOPstoEntity.get(op))
                        instancegetUCtoEntity.put(entity, cnt++);
                    }
                }
            }
            return instancegetUCtoEntity;

    }

    public static ArrayList<double[]> getUCtoE(){
        HashMap<String ,HashSet<String>> UCtoEntity = instancegetUCtoEntity;
        HashMap<String ,Integer> Epos = instancegetEntityPos;
        HashMap<Integer,String> postoUC = instancegetPostoUC;
        ArrayList<double[]> entityRelation = new ArrayList<double[]>();
        int x = Epos.size();
        int y = UCtoEntity.size();
        for(int cnt =0 ;cnt<y ;cnt++){
            double[] d = new double[x];
            String UCname = postoUC.get(cnt);
            HashSet<String> temp = UCtoEntity.get(UCname);
            if (temp == null){
                entityRelation.add(d);
                continue;
            }
            for(String ss : temp){
                int entity_Cnt = Epos.get(ss);
                d[entity_Cnt] = 1;
            }
            entityRelation.add(d);
        }
        return entityRelation;
    }
    public static ArrayList<ArrayList<Integer>> getRelatedEntity(){
        ArrayList<ArrayList<Integer>> matrix = new ArrayList<>();
        for (int i = 0; i < numofEntity; i++) {
            ArrayList<Integer> row = new ArrayList<>();
            for (int j = 0; j < numofEntity; j++) {
                row.add(0); // 初始化为 0，可以根据需要进行修改
            }
            matrix.add(row);
        }
        return matrix;
    }

    public static ArrayList<double[]> getdataSet(){
        DiagramParser parser = new DiagramParser();
        HashMap<String ,HashSet<String>> actortoUC = parser.instancegetActortoUC;
        ArrayList<double[]> entityRelation = new ArrayList<double[]>();   //用例与实体之间的关系矩阵
        HashMap<String ,Integer> Epos = parser.instancegetEntityPos; //实体名称对应的矩阵中的位置
        // ArrayList<double[]> EtoE = parser.instancegetEtoE; //实体与实体之间的关系矩阵
        HashMap<String , Integer> UCpos = parser.instancegetUCPos; //用例名称对应的矩阵中的位置
        HashMap<String ,HashSet<String>> UCtoEntity = parser.instancegetUCtoEntity; //用例与实体的对应关系
        HashMap<Integer,String> postoUC = parser.instancegetPostoUC;
        // HashMap<Integer,String> postoEntity = parser.instancegetPostoEntity;
/*
        Iterator <String> iterator = Epos.keySet().iterator();
        while(iterator.hasNext()){
            String Key = iterator.next();
            System.out.println(Key);
            System.out.println("实体的标号为" + Epos.get(Key));
        }
        for(int i = 0; i<EtoE.size() ; i++){
            for(int j = 0;j<EtoE.get(i).length;j++){
                System.out.print(EtoE.get(i)[j] + " ");
            }
            System.out.println();
        }


        Iterator <String> iterator2 = UCtoEntity.keySet().iterator();
        while(iterator2.hasNext()){
            String Key = iterator2.next();
            System.out.println("用例:"+Key +"的编号为"+UCpos.get(Key)+"  其关联的实体名称如下");
            HashSet<String> temp = UCtoEntity.get(Key);
            for(String ss : temp){
                System.out.println(ss);
            }
        }

 */
        int x = Epos.size();
        int y = UCpos.size();
        for(int cnt =0 ;cnt<y ;cnt++){
            double[] d = new double[x];
            String UCname = postoUC.get(cnt);
            HashSet<String> temp = UCtoEntity.get(UCname);
            if (temp == null){
                entityRelation.add(d);
                continue;
            }
            for(String ss : temp){
                int entity_Cnt = Epos.get(ss);
                d[entity_Cnt] = 1;
            }
            entityRelation.add(d);
        }
        /*
        System.out.println("用例与实体的关联矩阵为");
        for(int cnt =0 ;cnt<y ;cnt++){
            for(int cnt2 =0 ;cnt2<x ;cnt2++){
                System.out.print(entityRelation.get(cnt)[cnt2]+"  ");
            }
            System.out.println();
        }
        */

        ArrayList<double[]> dataSet = new ArrayList<double[]>();


        //根据UCtoE计算出UC之间的实体关联
        for (int i = 0; i < y; i++) {
            double[] inside = new double[y];
            for (int j = 0; j < y; j++) {
                if (i==3&&j==4){
                    int oo;
                }
                double count = 0;
                double bottom = 0, bottom2 = 0;
                for (int z = 0; z < x; z++) {
                    count = count + entityRelation.get(i)[z] * entityRelation.get(j)[z];
                    bottom += entityRelation.get(i)[z] * entityRelation.get(i)[z];
                    bottom2 += entityRelation.get(j)[z] * entityRelation.get(j)[z];
                }
                bottom = (double) Math.sqrt(bottom);
                bottom2 = (double) Math.sqrt(bottom2);
                // System.out.println(i);
                //System.out.println(j);
                if(bottom == 0 || bottom2 == 0){
                    if(bottom == 0 && bottom2 == 0)
                        inside[j] = 0.6;
                    else
                        inside[j] = 0;
                }
                else
                    inside[j] = count / (bottom * bottom2) * 0.6;
            }
            dataSet.add(inside);
            // System.out.println(Arrays.toString(inside));
        }

        Iterator <String> iterator3 = actortoUC.keySet().iterator();
        while(iterator3.hasNext()){
            String Key = iterator3.next();
            //System.out.println("用例:"+Key +"的编号为"+UCpos.get(Key)+"  其关联的实体名称如下");
            HashSet<String> temp = actortoUC.get(Key);
            for(String ss : temp){
                for (String sss:temp){
                    System.out.println(ss+" "+sss);
                    int from = UCpos.get(ss);
                    int to = UCpos.get(sss);
                    if(to > from)
                    {
                        dataSet.get(from)[to] += 0.2;
                        dataSet.get(to)[from] += 0.2;
                    }
                    else if(to == from)
                        continue;
                }
            }
        }
        for(int i=0 ;i< y; i++){
            dataSet.get(i)[i]+= 0.4;
        }
        return dataSet;
    }

    public static HashMap<String ,HashSet<String>> getUCtoEntity(){
        HashMap<String, HashSet<String>> instancegetUCtoEntity = new HashMap<>();
        for (Map.Entry<String, HashSet<String>> entry : instancegetUCtoOp.entrySet()) {
            String uc = entry.getKey();
            HashSet<String> ops = entry.getValue();
            HashSet<String> entities = new HashSet<>();

            for (String op : ops) {
                if (instancegetOPstoEntity.containsKey(op)) {
                    entities.addAll(instancegetOPstoEntity.get(op));
                }
            }

            instancegetUCtoEntity.put(uc, entities);
        }
        return instancegetUCtoEntity;
    }

    public static HashMap<Integer,String> getPostoEntity(){
        HashMap<String , Integer> temp = instancegetEntityPos;
        HashMap<Integer,String> result= new HashMap<>();
        Iterator <String> iterator = temp.keySet().iterator();
        while(iterator.hasNext()){
            String Key = iterator.next();
            int num = temp.get(Key);
            result.put(num,Key);
        }
        return result;
    }


    public static HashMap<Integer,String> getPostoUC(){
        HashMap<String , Integer> temp = instancegetUCPos;
        HashMap<Integer,String> result= new HashMap<>();
        Iterator <String> iterator = temp.keySet().iterator();
        while(iterator.hasNext()){
            String Key = iterator.next();
            int num = temp.get(Key);
            result.put(num,Key);
        }
        return result;
    }

    public static HashSet<String> getEntities(){
        HashSet<String> result = new HashSet<>();
        for (String tempactor : classs) {
                String textPath = tempactor;

                try (BufferedReader reader = new BufferedReader(new FileReader(textPath))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("@Data") || tempactor.contains("domain")){
                            String[] splitedClasses = tempactor.split("[\\\\/.]");
                            if(!splitedClasses[splitedClasses.length-2].endsWith("VO")  &&  !splitedClasses[splitedClasses.length-2].endsWith("BO"))
                                result.add(splitedClasses[splitedClasses.length-2]);
                            break;
                        }
                        if (line.contains("public") && line.contains("interface")){
                            String[] splitedClasses = tempactor.split("[\\\\/.]");
                            if(splitedClasses[splitedClasses.length-2].endsWith("Mapper")){
                                //String su6 = splitedClasses[splitedClasses.length-2];
                                String su7 = splitedClasses[splitedClasses.length-2].substring(0,splitedClasses[splitedClasses.length-2].length()-"Mapper".length());
                                result.add(su7);
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }

        return result;
    }

       /* instancegetPostoUC = getPostoUC(nameofRemodel);
        instancegetPostoEntity = getPostoEntity(nameofRemodel);


        instancegetUCtoE = getUCtoE(nameofRemodel);
        instancegetUCtoEntity = getUCtoEntity(nameofRemodel);

        instancegetEtoE = getEtoE(nameofRemodel);

        numofEntity = instancegetEtoE.size();
        numofUC = instancegetPostoUC.size();
        DataSet = getdataSet();
        instancegetRelatedEntity = getRelatedEntity(nameofRemodel);*


/*    private static String readMermaidFile(String filePath) {
        try {
            return  Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }*/

    public static String extractFileName(String filePath) {
        // 创建File对象
        File file = new File(filePath);

        // 获取文件名（不包括扩展名）
        String fileName = file.getName();

        // 如果文件名包含有扩展名，移除扩展名部分
        int dotIndex = fileName.lastIndexOf(".");
        if (dotIndex != -1) {
            fileName = fileName.substring(0, dotIndex);
        }

        return fileName;
    }

/*    public static HashMap<String, HashSet<String>> getActortoUC(){
        HashMap<String, HashSet<String>> result = new HashMap<>();
        HashSet<String> controllers = new HashSet<>();
        for (String tempactor : classs) {
            //tempactor = extractFileName(tempactor);
            if (tempactor.contains("ontroller")){
                controllers.add(tempactor);
            }
        }
        result.put(Mname, controllers);
        return result;
    }*/

/*    public static HashMap<String , Integer> getUCPos(){
        HashMap<String , Integer> temp = new HashMap<>();
        int cnt = 0;
        for (String tempactor : classs) {
            //tempactor = extractFileName(tempactor);
            if (tempactor.contains("ontroller")){
                temp.put(tempactor,cnt++);
            }
        }
        return temp;
    }*/


    public static HashMap<String, Integer> getUCPos(){
        HashMap<String , Integer> temp = new HashMap<>();
        int cnt = 0;
        for (String tempactor : classs) {
            //System.out.println(tempactor+"asdasdasd");
            HashSet<String> methods = new HashSet<>();
            if (tempactor.contains("ontroller")){
                //Method[] methodsType = tempactor.getMethods();
/*                for(Method m:methodsType){
                    if (m.getDeclaringClass() == Object.class){
                        continue;
                    }
                    System.out.println(m.getName());
                    methods.add(m.getName());
                }*/
                String textPath = tempactor;
                methods.addAll(extractMethodNames(textPath));

                for(String tempString: methods){
                    if (!temp.containsKey(tempString))
                        temp.put(tempString,cnt++);
                }
            }

        }
        return temp;
    }

    public static HashMap<String, HashSet<String>> getActortoUC(){
        HashMap<String, HashSet<String>> result = new HashMap<>();
        for (String tempactor : classs) {
            //System.out.println(tempactor+"asdasdasd");
            HashSet<String> methods = new HashSet<>();
            if (tempactor.contains("ontroller")){
                //Method[] methodsType = tempactor.getMethods();
/*                for(Method m:methodsType){
                    if (m.getDeclaringClass() == Object.class){
                        continue;
                    }
                    System.out.println(m.getName());
                    methods.add(m.getName());
                }*/

                String textPath = tempactor;
                String[] parts = tempactor.split("[\\\\/]");



                tempactor = parts[parts.length-1];
                String[] parts2 = tempactor.split("\\.");

                tempactor = parts2[0];
                if (tempactor.contains("controller")){
                    tempactor = tempactor.substring(0, tempactor.length() - "controller".length());
                }
                else if (tempactor.contains("Controller")){
                    tempactor = tempactor.substring(0, tempactor.length() - "Controller".length());
                }


                methods.addAll(extractMethodNames(textPath));
                if (!methods.isEmpty())
                    result.put(tempactor, methods);
            }

        }
        return result;
    }
    public static HashMap<String, HashSet<String>> getUCtoOp() throws IOException {
        HashMap<String, HashSet<String>> result = new HashMap<>();
        for (String tempactor : classs) {
            //System.out.println(tempactor+"asdasdasd");
            HashSet<String> methods = new HashSet<>();
            if (tempactor.contains("ontroller")){
                //Method[] methodsType = tempactor.getMethods();
/*                for(Method m:methodsType){
                    if (m.getDeclaringClass() == Object.class){
                        continue;
                    }
                    System.out.println(m.getName());
                    methods.add(m.getName());
                }*/
                String textPath = tempactor;
                methods.addAll(extractMethodNames(textPath));
                //result.put(tempactor, methods);
                for (String method:methods) {
                    HashSet<String> systemOperations = getAllOperations(textPath, method);
                    result.put(method, systemOperations);
                }
            }

        }
        return result;
    }

    public static HashSet<String> getAllOperations(String textPath, String methodName) throws IOException {
        HashSet<String> re = new HashSet<>();
        String getOpText = getMethodBlock(textPath, methodName);
        String extractedReturnLine = extractReturnLine(getOpText);
        String[] methodComponents = extractMethodComponents(extractedReturnLine);
        if(methodComponents[1].equals("this")){
            methodComponents[1] = methodComponents[2];
            methodComponents[2] = methodComponents[3];
        }
        String serviceName = methodComponents[1];
        serviceName = serviceName.substring(1);


        String serviceClass = null;
        for (String aClass : classs) {
            if(aClass.contains(serviceName) ){
                serviceClass = aClass;
                break;
            }
        }
        if(serviceClass == null){
            return re;
        }

        String textPath2 = serviceClass;
        String getServiceOpText2 = getMethodBlock(textPath2,methodComponents[2]);

        re.addAll(findOpsByOtherService(getServiceOpText2));
        re.addAll(findOpByThis(getServiceOpText2));
        re.addAll(findOpByMapper(getServiceOpText2));
        re.addAll(findOpByClass(getServiceOpText2));

        return re;
    }

    public static List<String> extractMethodNames(String filePath) {

        List<String> methodNames = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            StringBuilder fileContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line).append("\n");
            }


            String content = fileContent.toString();
            //Pattern methodPattern = Pattern.compile("([a-zA-Z_]\\w*)\\s*\\([^)]*\\)[\\w\\s]*\\{");
            Pattern methodPattern = Pattern.compile("\\s+(\\w+)\\(");

            Matcher matcher = methodPattern.matcher(content);

            while (matcher.find()) {
                methodNames.add(matcher.group(1));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return methodNames;
    }

/*    public static HashMap<String, HashSet<String>> getOPstoEntity() throws IOException {
        HashMap<String, HashSet<String>> re = new HashMap<>();
        int cnt = 0;
        for(Map.Entry<String, HashSet<String>> entry : instancegetUCtoOp.entrySet()){
            HashSet<String> ops = entry.getValue();
            for (String opNmae: ops) {
                String textPath = entry.getKey();
                String getOpText = getMethodBlock(textPath, opNmae);

                String extractedReturnLine = extractReturnLine(getOpText);
*//*
                System.out.println("Extracted Return Line: " + extractedReturnLine);
*//*

                String[] methodComponents = extractMethodComponents(extractedReturnLine);
                if(methodComponents[1].equals("this")){
                    methodComponents[1] = methodComponents[2];
                    methodComponents[2] = methodComponents[3];
                }
*//*                System.out.println("Bookshelf Service: " + methodComponents[1]);
                System.out.println("Method Name: " + methodComponents[2]);*//*


                String serviceName = methodComponents[1] + "Impl";
                serviceName = serviceName.substring(1);

                String serviceClass = null;
                for (String aClass : classs) {
                    if(aClass.contains(serviceName) ){
                        serviceClass = aClass;
                        break;
                    }
                }
                if(serviceClass == null){
                    continue;
                }

                String textPath2 = serviceClass;


*//*                int test = 0;
                if (methodComponents[2].equals("getBookChapterListByBookId")){
                    test =1;

                }*//*

                String getServiceOpText2 = getMethodBlock(textPath2,methodComponents[2]);

                HashSet<String> mappers = findMapper(getServiceOpText2);
                mappers.addAll(findClass(getServiceOpText2));
                mappers.addAll(findVo(getServiceOpText2));
                mappers.addAll(findData(getServiceOpText2));
                for (String tempString : mappers){
                    if (!instancegetEntityPos.containsKey(tempString)){
                        instancegetEntityPos.put(tempString,cnt++);
                    }
                }
                re.put( opNmae , mappers);
            }
        }

        return re;
    }*/

    private static HashSet<String> findData(String code) {

        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            String[] parts = line.split("[\\s\\.<>()]+");
            for (String part : parts) {
                String part2 = part.toLowerCase();
                if (part2.endsWith("vo") || part2.endsWith("bo")){
                    part2 = part2.substring(0,part2.length()-2);
                }
                int flag = 0;
                for(String tempE : entities){
                    String y = tempE.toLowerCase();
                    if (y.equals(part2)){
                        mappers.add(tempE);
                        break;
                    }
                }
            }
        }
        return mappers;
    }


    private static HashSet<String> findClass(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {

            int test  =  0;
            if (line.contains("BookChapter.class")){
                test++;
            }
            // 检查是否包含关键字"class"
            if (line.contains(".class")) {
                // 按照"."拆分该行
                String[] parts = line.split("[\\(\\)\\s]+");

                // 寻找包含"class"的部分
                for (String part : parts) {
                    if (part.contains(".class")) {
                        String[] parts2 = part.split("\\.");
                        if (!parts2[0].endsWith("VO"))
                            mappers.add(parts2[0]);
                        else
                            mappers.add(parts2[0].substring(0,parts2[0].length()-2));
                    }
                }
            }
        }
        return mappers;
    }

    private static HashSet<String> findOpsByOtherService(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            // 检查是否包含关键字"Service"
            if (line.contains("Service")) {
                Pattern pattern = Pattern.compile("([a-zA-Z]+)Service\\.([a-zA-Z]+)\\(");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String opName = matcher.group(1)+"Service."+matcher.group(2);
                    String tempOpName = opName.toLowerCase();
                    if (tempOpName.contains("logger") /*|| tempOpName.equals("insert")*/ || tempOpName.equals("delete") || tempOpName.equals("append") || tempOpName.contains("tostring") || tempOpName.equals("get") || tempOpName.contains("size")  || tempOpName.equals("add")){
                        continue;
                    }
                    int test = 0;
                    if (line.contains("getHashListVal")){
                        System.out.print("dsfsdfs");
                    }
                    HashSet<String> mappers2 = findMapper(line);
                    mappers2.addAll(findClass(line));
                    mappers2.addAll(findVo(line));
                    mappers2.addAll(findData(line));

                    HashSet<String> mappers3 = instancegetOPstoEntity.getOrDefault(opName, new HashSet<>());
                    for (String tempS: mappers2) {
                        int flag = 0;
                        for(String tempE: entities){
                            String x = tempS.toLowerCase();
                            String y = tempE.toLowerCase();
                            if (x.equals(y) || y.contains(x) || x.contains(y)){
                                flag = 1;
                                break;
                            }
                        }
                        if (flag == 1){
                            mappers3.add(tempS);
                            if (!instancegetEntityPos.containsKey(tempS)){
                                instancegetEntityPos.put(tempS,instancegetEntityPos.size());
                            }
                        }
                    }
                    if (!mappers3.isEmpty()){
                        instancegetOPstoEntity.put(opName, mappers3);
                        mappers.add(opName);
                    }
                }
            }
        }
        return mappers;
    }



    private static HashSet<String> findOpByMapper(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            // 检查是否包含关键字"Service"
            if (line.contains("Mapper")) {
                Pattern pattern = Pattern.compile("([a-zA-Z]+)Mapper\\.([a-zA-Z]+)\\(");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String opName = matcher.group(1)+"Mapper."+matcher.group(2);
                    String tempOpName = opName.toLowerCase();
                    if (tempOpName.contains("logger") /*|| tempOpName.equals("insert") */|| tempOpName.equals("delete") || tempOpName.equals("append") || tempOpName.contains("tostring") || tempOpName.equals("get") || tempOpName.contains("size")  || tempOpName.equals("add")){
                        continue;
                    }
                    //mappers.add(opName);
                    HashSet<String> mappers2 = findMapper(line);
                    mappers2.addAll(findClass(line));
                    mappers2.addAll(findVo(line));
                    mappers2.addAll(findData(line));

                    HashSet<String> mappers3 = instancegetOPstoEntity.getOrDefault(opName, new HashSet<>());
                    for (String tempS: mappers2) {
                        int flag = 0;
                        for(String tempE: entities){
                            String x = tempS.toLowerCase();
                            String y = tempE.toLowerCase();
                            if (x.equals(y) || y.contains(x) || x.contains(y)){
                                flag = 1;
                                break;
                            }
                        }
                        if (flag == 1){
                            mappers3.add(tempS);
                            if (!instancegetEntityPos.containsKey(tempS)){
                                instancegetEntityPos.put(tempS,instancegetEntityPos.size());
                            }
                        }
                    }
                    if (!mappers3.isEmpty()){
                        instancegetOPstoEntity.put(opName, mappers3);
                        mappers.add(opName);
                    }                 }
            }
        }
        return mappers;
    }

    private static HashSet<String> findOpByClass(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            if (line.contains("return") || line.contains("if")){
                continue;
            }
            if (true) {
                Pattern pattern = Pattern.compile("([a-zA-Z]+)\\.([a-zA-Z]+)\\(");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String opName = matcher.group(1)+"."+matcher.group(2);
                    String tempOpName = opName.toLowerCase();
                    if (tempOpName.contains("logger") /*|| tempOpName.equals("insert")*/ || tempOpName.equals("delete") || tempOpName.equals("append") || tempOpName.contains("tostring") || tempOpName.equals("get") || tempOpName.contains("size")  || tempOpName.equals("add")){
                        continue;
                    }

                    //mappers.add(opName);
                    HashSet<String> mappers2 = findMapper(line);
                    mappers2.addAll(findClass(line));
                    mappers2.addAll(findVo(line));
                    mappers2.addAll(findData(line));

                    HashSet<String> mappers3 = instancegetOPstoEntity.getOrDefault(opName, new HashSet<>());
                    for (String tempS: mappers2) {
                        int flag = 0;
                        for(String tempE: entities){
                            String x = tempS.toLowerCase();
                            String y = tempE.toLowerCase();
                            if (x.equals(y) || y.contains(x) || x.contains(y)){
                                flag = 1;
                                break;
                            }
                        }
                        if (flag == 1){
                            mappers3.add(tempS);
                            if (!instancegetEntityPos.containsKey(tempS)){
                                instancegetEntityPos.put(tempS,instancegetEntityPos.size());
                            }
                        }
                    }
                    if (!mappers3.isEmpty()){
                        instancegetOPstoEntity.put(opName, mappers3);
                        mappers.add(opName);
                    }                }
            }
        }
        return mappers;
    }

    private static HashSet<String> findOpByThis(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            // 检查是否包含关键字"Service"
            if (line.contains("this")) {
                Pattern pattern = Pattern.compile("this\\.([a-zA-Z]+)\\(");
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String opName = matcher.group(1);
                    String tempOpName = opName.toLowerCase();
                    if (tempOpName.contains("logger") /*|| tempOpName.equals("insert")*/ || tempOpName.equals("delete") || tempOpName.equals("append") || tempOpName.contains("tostring") || tempOpName.equals("get") || tempOpName.contains("size")  || tempOpName.equals("add")){
                        continue;
                    }
                    //mappers.add(opName);
                    HashSet<String> mappers2 = findMapper(line);
                    mappers2.addAll(findClass(line));
                    mappers2.addAll(findVo(line));
                    mappers2.addAll(findData(line));

                    HashSet<String> mappers3 = instancegetOPstoEntity.getOrDefault(opName, new HashSet<>());
                    for (String tempS: mappers2) {
                        int flag = 0;
                        for(String tempE: entities){
                            String x = tempS.toLowerCase();
                            String y = tempE.toLowerCase();
                            if (x.equals(y) || y.contains(x) || x.contains(y)){
                                flag = 1;
                                break;
                            }
                        }
                        if (flag == 1){
                            if (!instancegetEntityPos.containsKey(tempS)){
                                instancegetEntityPos.put(tempS,instancegetEntityPos.size());
                            }
                            mappers3.add(tempS);
                        }
                    }
                    if (!mappers3.isEmpty()){
                        instancegetOPstoEntity.put(opName, mappers3);
                        mappers.add(opName);
                    }                }
            }
        }
        return mappers;
    }


    private static HashSet<String> findMapper(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            // 检查是否包含关键字"Mapper"
            if (line.contains("Mapper")) {
                // 按照"."拆分该行
                String[] parts = line.split("[\\s\\.<>()]+");

                // 寻找包含"mapper"的部分
                for (String part : parts) {
                    if (part.contains("Mapper")) {
                        String result = part.substring(0, 1).toUpperCase() + part.substring(1);

                        mappers.add( result.substring(0, result.length() - "Mapper".length()));
                    }
                }
            }
        }
        return mappers;
    }

    private static HashSet<String> findVo(String code) {
        String[] lines = code.split("\n");
        HashSet<String> mappers = new HashSet<>();
        for (String line : lines) {
            // 检查是否包含关键字"VO"
            if (line.contains("VO")) {
                // 按照"."拆分该行
                String[] parts = line.split("[\\s\\r.<>()]");

                for (String part : parts) {
                    if (part.contains("VO")&&Character.isUpperCase(part.charAt(0))&&part.matches("[a-zA-Z]+")) {
                            String result = part.substring(0, 1).toUpperCase() + part.substring(1);
                            mappers.add( result.substring(0, result.length() - "VO".length()));
                    }
                }
            }
        }
        return mappers;
    }

    private static String extractReturnLine(String code) {
        // 使用正则表达式匹配包含 return 的行
        Pattern pattern = Pattern.compile("\\breturn\\s+.*?;");
        Matcher matcher = pattern.matcher(code);

        // 查找匹配项
        if (matcher.find()) {
            return matcher.group();
        }

        return "Return line not found";
    }
    private static String[] extractMethodComponents(String returnLine) {
        // 按照 "." 进行拆分
        String[] parts = returnLine.split("[\\.\\(\\)\\s]+");
        // 获取 bookshelfService 和 userBookshelfExistBook
        return parts;

    }





    private static String getMethodBlock(String textPath, String targetMethodName) throws IOException {
        StringBuilder methodBlock = new StringBuilder();
        boolean insideMethod = false;
        int numofQuo = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(textPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 检查是否是方法的开始
                if (line.contains(targetMethodName) && line.contains("{")) {
                    numofQuo++;
                    insideMethod = true;
                    continue;
                }

                // 如果在方法内，添加当前行到方法块
                if (insideMethod) {
                    methodBlock.append(line).append("\n");
                    for (int i = 0; i < line.length(); i++) {
                        if (line.charAt(i) == '{'){
                            numofQuo++;
                        }
                        else if (line.charAt(i) == '}'){
                            numofQuo--;
                        }
                    }
                    if (numofQuo == 0){
                        break;
                    }
                }
            }
        }

        return methodBlock.toString();
    }

}
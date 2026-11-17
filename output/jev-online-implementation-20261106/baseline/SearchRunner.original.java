package tuning;

import GA.*;
import Evaluation.*;
import com.debacharya.nsgaii.*;
import com.debacharya.nsgaii.datastructure.*;
import com.debacharya.nsgaii.objectivefunction.AbstractObjectiveFunction;
import java.util.*;

/** Keeps the legacy operators/objectives, controls randomness, budget and final elitism. */
public final class SearchRunner {
    public static final String VERSION="seeded-legacy-nsga2-v1";
    public static final String METRICS="legacy-ifn-average-v1";
    private long evaluations;
    private Population prepare(NSGA2 ga, Population population) {
        for (Chromosome c:population.getPopulace()) {
            // Clear rank/crowding state from previous preparations, retain independent genes.
            c.reset();
            c.setCrowdingDistance(0);
            if (!valid(genes(c))) throw new IllegalStateException("Operator produced invalid chromosome");
        }
        Population prepared=ga.preparePopulation(population);
        evaluations+=population.size();
        for(Chromosome c:prepared.getPopulace()) for(double v:c.getObjectiveValues())
            if(!Double.isFinite(v)) throw new IllegalStateException("Non-finite legacy objective");
        return prepared;
    }
    public static List<AbstractObjectiveFunction> objectives() {
        return Arrays.asList(new Ob1(),new Ob2(),new Ob3(),new Ob4(),new Ob5());
    }
    public RunResult run(RunConfig cfg) {
        cfg.validate(); evaluations=0; SearchRandom.seed(cfg.seed);
        if(DiagramParser.numofUC<1) throw new IllegalArgumentException("Input contains no use cases");
        if(DiagramParser.numofUC>=1000) throw new IllegalArgumentException("Legacy initializer supports fewer than 1000 use cases");
        long start=System.nanoTime();
        Configuration config=new Configuration(objectives());
        config.setPopulationSize(cfg.populationSize);
        config.setGenerations(cfg.generations);
        config.setChromosomeLength(DiagramParser.numofUC+DiagramParser.numofEntity);
        config.completeSilence();
        NSGA2 ga=new NSGA2(config);
        Initpop init=new Initpop(); GeneticCode code=new GeneticCode();
        Mycross crossover=new Mycross(new MyParticipantCreator(),cfg.crossoverProbability,cfg.crossP);
        Childpop children=new Childpop();
        Population parent=prepare(ga,init.produce(cfg.populationSize,config.getChromosomeLength(),code,null));
        Population child=prepare(ga,children.produce(parent,crossover,new Mymutation(),cfg.populationSize));
        int completed=1;
        // Reserve 2N evaluations for the final P+Q merge. A further generation costs 3N.
        while(cfg.evaluationBudget==0 ? completed<cfg.generations :
              evaluations+5L*cfg.populationSize<=cfg.evaluationBudget) {
            parent=ga.getChildFromCombinedPopulation(prepare(ga,Service.combinePopulation(parent,child)));
            child=prepare(ga,children.produce(parent,crossover,new Mymutation(),cfg.populationSize));
            completed++;
        }
        Population elite=ga.getChildFromCombinedPopulation(prepare(ga,Service.combinePopulation(parent,child)));
        RunResult result=new RunResult(); result.config=cfg; result.evaluations=evaluations;
        result.completedGenerations=completed;
        result.unusedBudget=cfg.evaluationBudget==0?0:cfg.evaluationBudget-evaluations;
        result.searchMillis=(System.nanoTime()-start)/1e6;
        long exportStart=System.nanoTime();
        Map<String,RunResult.SolutionRecord> unique=new TreeMap<>();
        result.populationSize=elite.size(); int validCount=0;
        for(Chromosome c:elite.getPopulace()) {
            if(!valid(genes(c))) {result.invalidCount++; continue;}
            validCount++;
            RunResult.SolutionRecord row=record(c);
            result.population.add(row); unique.putIfAbsent(Arrays.toString(row.chromosome),row);
        }
        result.validRate=(double)validCount/elite.size(); result.validUniqueCount=unique.size();
        result.front=Quality.front(new ArrayList<>(unique.values()));
        result.nonDominatedCount=objectivePointCount(result.front);
        result.evaluationExportMillis=(System.nanoTime()-exportStart)/1e6;
        return result;
    }
    public static int objectivePointCount(List<RunResult.SolutionRecord> front) {
        Set<String> points=new HashSet<>();for(RunResult.SolutionRecord s:front)points.add(Arrays.toString(s.objectives));return points.size();
    }
    public static int[] genes(Chromosome c) {
        int[] a=new int[c.getLength()]; for(int i=0;i<a.length;i++) a[i]=(Integer)c.getAllele(i).getGene(); return a;
    }
    public static boolean valid(int[] genes) {
        int u=DiagramParser.numofUC;
        if(u<1 || genes.length!=u+DiagramParser.numofEntity) return false;
        Set<Integer> services=new HashSet<>();
        for(int i=0;i<u;i++){ if(genes[i]<0 || genes[i]>=u)return false; services.add(genes[i]); }
        for(int i=u;i<genes.length;i++) if(!services.contains(genes[i])) return false;
        return true;
    }
    public static int[] canonical(int[] genes) {
        Map<Integer,Integer> labels=new LinkedHashMap<>(); int[] out=genes.clone();
        for(int i=0;i<out.length;i++){if(!labels.containsKey(genes[i]))labels.put(genes[i],labels.size());out[i]=labels.get(genes[i]);}
        return out;
    }
    public static RunResult.SolutionRecord record(Chromosome c) {
        RunResult.SolutionRecord row=new RunResult.SolutionRecord();
        row.chromosome=canonical(genes(c)); row.objectives=new double[5]; row.metrics=new double[5];
        for(int j=0;j<5;j++){row.objectives[j]=c.getObjectiveValue(j);row.metrics[j]=(j==0?-1:1)*row.objectives[j];}
        for(int i=0;i<DiagramParser.numofUC;i++) row.serviceCount=Math.max(row.serviceCount,row.chromosome[i]+1);
        for(int s=0;s<row.serviceCount;s++) {
            Map<String,Object> service=new LinkedHashMap<>(); List<String> uc=new ArrayList<>(),entities=new ArrayList<>();
            service.put("id",s);
            for(int i=0;i<row.chromosome.length;i++) if(row.chromosome[i]==s) {
                if(i<DiagramParser.numofUC) uc.add(DiagramParser.instancegetPostoUC.get(i));
                else entities.add(DiagramParser.instancegetPostoEntity.get(i-DiagramParser.numofUC));
            }
            service.put("useCases",uc); service.put("entities",entities); row.services.add(service);
        }
        // Export required/provided entity interfaces using the unchanged legacy interface calculation.
        ArrayList<ArrayList<Integer>> useCases=new ArrayList<>(),entities=new ArrayList<>();
        for(int s=0;s<row.serviceCount;s++){useCases.add(new ArrayList<>());entities.add(new ArrayList<>());}
        for(int i=0;i<row.chromosome.length;i++) {
            if(i<DiagramParser.numofUC)useCases.get(row.chromosome[i]).add(i);
            else entities.get(row.chromosome[i]).add(i-DiagramParser.numofUC);
        }
        int[][][] interfaces=QualityCalculate.generateInterface(useCases,entities);
        for(int s=0;s<row.serviceCount;s++) {
            List<String> required=new ArrayList<>(),provided=new ArrayList<>();
            for(int e=0;e<DiagramParser.numofEntity;e++) {
                if(interfaces[0][s][e]==1)required.add(DiagramParser.instancegetPostoEntity.get(e));
                if(interfaces[1][s][e]==1)provided.add(DiagramParser.instancegetPostoEntity.get(e));
            }
            row.services.get(s).put("requiredEntityInterfaces",required);row.services.get(s).put("providedEntityInterfaces",provided);
        }
        return row;
    }
}

package GA;

import GA.DiagramParser;
import com.debacharya.nsgaii.crossover.AbstractCrossover;
import com.debacharya.nsgaii.crossover.CrossoverParticipantCreator;
import com.debacharya.nsgaii.datastructure.AbstractAllele;
import com.debacharya.nsgaii.datastructure.Chromosome;
import com.debacharya.nsgaii.datastructure.IntegerAllele;
import com.debacharya.nsgaii.datastructure.Population;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Mycross extends AbstractCrossover {
    public Mycross(CrossoverParticipantCreator crossoverParticipantCreator) {
        super(crossoverParticipantCreator);
    }

    public Mycross(CrossoverParticipantCreator creator, double trigger, double move) {
        super(creator);
        this.crossoverProbability = (float) trigger;
        this.moveProbability = (float) move;
    }
    private float moveProbability = 0.5F;
    /** Changes only the trigger; internal move probability and RNG semantics stay unchanged. */
    public void setTriggerProbability(double probability) {
        if(!Double.isFinite(probability) || probability<0 || probability>1)
            throw new IllegalArgumentException("Invalid trigger probability");
        this.crossoverProbability=(float)probability;
    }
    @Override public boolean shouldPerformCrossover() {
        return SearchRandom.nextFloat() <= this.crossoverProbability;
    }

    public List<Chromosome> perform(Population population) {
        List<Chromosome> result = new ArrayList();
        List<Chromosome> selected = this.crossoverParticipantCreator.create(population);
        if (this.shouldPerformCrossover()) {
            result.add(this.prepareChildChromosome((Chromosome)selected.get(0)));

        } else {
            result.add(((Chromosome)selected.get(0)).getCopy());
           // result.add(((Chromosome)selected.get(1)).getCopy());
        }

        return result;
    }

    private Chromosome prepareChildChromosome(Chromosome chromosome1) {
        List<AbstractAllele> geneticCode = new ArrayList();
        int UCnum = DiagramParser.numofUC;
        float crossP = moveProbability;
        int numofMS = 0;
        int[] mapp = new int[UCnum+1];
        ArrayList<Integer> origin = new ArrayList<>();

        for (int i = 0; i < mapp.length; i++) {
            mapp[i] = 0;
        }
        for (int i = 0; i < UCnum; i++) {
            int va =(Integer)chromosome1.getGeneticCode().get(i).getGene();
            origin.add(va);
            numofMS = Math.max(numofMS,va);
            mapp[va]++;
        }
        for (int i = UCnum; i < chromosome1.getGeneticCode().size(); i++) {
            int va =(Integer)chromosome1.getGeneticCode().get(i).getGene();
            origin.add(va);
        }

        for(int i = 0; i < UCnum; ++i) {

           // for (int j = 0; j < origin.size(); j++) {
           //     System.out.print(origin.get(j)+"  ");
           // }
           //System.out.println();
            float temp = SearchRandom.nextFloat();
           // System.out.print(temp+"   ");
            if (temp<= crossP){
               // System.out.println("交叉");
                int pos = SearchRandom.nextInt(numofMS+2);
                int Msid = origin.get(i);

                if (Msid == pos){
                    continue;
                }

                if (mapp[Msid] == 1){
                    //原微服务中只包含一个元素
                    if(pos == numofMS+1){ //分配到一个空的微服务
                        continue;
                    }
                    origin.set(i,pos);
                    mapp[Msid]--;
                    mapp[pos]++;
                    for (int j = UCnum; j < chromosome1.getGeneticCode().size(); j++) {
                        if (origin.get(j)==Msid)
                            origin.set(j,pos);
                    }

                    //参与了微服务合并
                    for (int j = 0; j < chromosome1.getGeneticCode().size(); j++) {
                        if (origin.get(j) > Msid){
                            origin.set(j,origin.get(j)-1);  //所属微服务编号-1
                        }
                    }
                    for (int j = 0; j <= numofMS-1; j++) {
                        if (j >= Msid){
                            mapp[j] = mapp[j+1];
                        }
                    }
                    numofMS--;
                }
                else{
                    if (pos == numofMS+1)
                        numofMS++;
                    origin.set(i,pos);
                    mapp[pos]++;
                    mapp[Msid]--;
                }
            }

        }

        for (int i = UCnum; i < chromosome1.getGeneticCode().size(); i++) {
            if (SearchRandom.nextFloat() <= crossP){
                int pos = SearchRandom.nextInt(numofMS+1);
                origin.set(i,pos);
            }
        }

        for (int j = 0; j < DiagramParser.instancegetRelatedEntity.size(); j++) {
            int firstID = DiagramParser.instancegetRelatedEntity.get(j).get(0)+DiagramParser.numofUC;
            for (int k = 1; k < DiagramParser.instancegetRelatedEntity.get(j).size(); k++) {
                int secondID = DiagramParser.instancegetRelatedEntity.get(j).get(k)+DiagramParser.numofUC;
                origin.set(secondID,origin.get(firstID));
            }
        }


        for (int i = 0; i < origin.size(); i++) {
            geneticCode.add(i, new IntegerAllele(origin.get(i)));
        }

        //增加继承关系约束

        return new Chromosome(geneticCode);
    }


  /*
    @Override
    public Mycross(CrossoverParticipantCreator creator, double trigger, double move) {
        super(creator);
        this.crossoverProbability = (float) trigger;
        this.moveProbability = (float) move;
    }
    private float moveProbability = 0.5F;
    @Override public boolean shouldPerformCrossover() {
        return SearchRandom.nextFloat() <= this.crossoverProbability;
    }

    public List<Chromosome> perform(Population population) {
        int numUC = GA.DiagramParser.numofUC;
        int numEntity =GA.DiagramParser.numofEntity;
        List<Chromosome> parent = this.crossoverParticipantCreator.create(population);
        TreeSet<Integer> ss = new TreeSet<>();
        HashMap<Integer,Integer> mapp = new HashMap<>();





        for (int i = 0; i < numUC+numEntity; i++) {
            IntegerAllele msid = (IntegerAllele) parent.getGeneticCode().get(i);
            int msidnum = msid.getGene();
            ss.add(msidnum);
        }
        int cnt = 0;
        for (Integer t:ss){
            mapp.put(t,cnt);
            cnt++;
        }
        int UCrandomPos = SearchRandom.nextInt(numUC);
        int UCrandomValue = SearchRandom.nextInt(cnt+1);

        int EntityrandomPos = SearchRandom.nextInt(numEntity) + numUC;
        int EntityrandomValue = SearchRandom.nextInt(cnt+1);

        List<IntegerAllele> newcodew = new ArrayList();
        for (int i = 0; i < numUC; ++i) {
            if (i!=UCrandomPos)
                newcodew.add(i, new IntegerAllele(mapp.get(parent.getGeneticCode().get(i).getGene())));
            else
                newcodew.add(i,new IntegerAllele(UCrandomValue));
        }

        for (int i = numUC; i < numUC+numEntity; ++i) {
            if (i!= EntityrandomPos)
                newcodew.add(i, new IntegerAllele(mapp.get(parent.getGeneticCode().get(i).getGene())));//初始的微服务数量为actor的个数
            else
                newcodew.add(i,new IntegerAllele(EntityrandomValue));
        }

        List<Chromosome> populace = new ArrayList();
        populace.add(new Chromosome(newcodew));
        return populace;
         */

}

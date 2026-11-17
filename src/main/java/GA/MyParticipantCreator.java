package GA;

import com.debacharya.nsgaii.Service;
import com.debacharya.nsgaii.crossover.AbstractCrossover;
import com.debacharya.nsgaii.crossover.CrossoverParticipantCreator;
import com.debacharya.nsgaii.datastructure.Chromosome;
import com.debacharya.nsgaii.datastructure.Population;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class MyParticipantCreator implements CrossoverParticipantCreator {

    @Override
    public List<Chromosome> create(Population p) {
        List<Chromosome> selected = new ArrayList();
        Chromosome a = p.get(SearchRandom.nextInt(p.size()));
        Chromosome b = p.get(SearchRandom.nextInt(p.size()));
        Chromosome parent1 = a.getRank() < b.getRank() ? a :
                b.getRank() < a.getRank() ? b :
                a.getCrowdingDistance() > b.getCrowdingDistance() ? a :
                b.getCrowdingDistance() > a.getCrowdingDistance() ? b :
                SearchRandom.nextInt(2) == 0 ? a : b;

//        Chromosome parent2;
  //      for(parent2 = Service.crowdedBinaryTournamentSelection(p); parent1.identicalGeneticCode(parent2); parent2 = Service.crowdedBinaryTournamentSelection(p)) {
    //    }

        selected.add(0, parent1);
        //selected.add(1, parent2);
        return selected;
    }



}


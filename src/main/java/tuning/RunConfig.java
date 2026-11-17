package tuning;

public class RunConfig {
    public String id;
    public int populationSize;
    public int generations;
    public double crossoverProbability;
    public double crossP;
    public long seed = 11;
    /** Zero means run the nominal number of generations. */
    public long evaluationBudget;
    public RunConfig() {}
    public RunConfig(String id, int n, int g, double pc, double move) {
        this.id=id; populationSize=n; generations=g; crossoverProbability=pc; crossP=move;
    }
    public void validate() {
        if (id == null || !id.matches("[A-Za-z0-9_-]+") || populationSize<2 || generations<1 ||
            !Double.isFinite(crossoverProbability) || crossoverProbability<0 || crossoverProbability>1 ||
            !Double.isFinite(crossP) || crossP<0 || crossP>1 || evaluationBudget<0)
            throw new IllegalArgumentException("Invalid run config: " + id);
        if (evaluationBudget>0 && evaluationBudget<4L*populationSize)
            throw new IllegalArgumentException("Budget must cover initial P, Q and final combined evaluation");
    }
}

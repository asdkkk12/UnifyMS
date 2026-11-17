package GA;
import java.util.Random;
/** One stream per search; seeded by the runner. */
public final class SearchRandom {
    private static final ThreadLocal<Random> RNG = ThreadLocal.withInitial(() -> new Random(11L));
    private SearchRandom() {}
    public static void seed(long seed) { RNG.set(new Random(seed)); }
    public static int nextInt(int bound) { return RNG.get().nextInt(bound); }
    public static float nextFloat() { return RNG.get().nextFloat(); }
}

package tuning;

import java.util.*;

/** Experimental online policy; separate from the frozen C0-C5 table. */
public final class OnlineControlOptions {
    public String mode="off";
    public boolean monitor=false;
    public int intervalGenerations=10;
    public int historyWindows=3;
    public double step=.05, minProbability=.10, maxProbability=.90;
    public double noveltyThreshold=.20, gainThreshold=.0001;
    public int requestTimeoutMillis=5000;
    public String replayFile;
    public boolean enabled(){return monitor || !"off".equals(mode);}
    public void validate() {
        if(!Arrays.asList("off","rule","jev","replay").contains(mode) || intervalGenerations<1 || historyWindows<1 ||
           requestTimeoutMillis<1 || !Double.isFinite(step) || step<=0 || step>1 ||
           !Double.isFinite(minProbability) || !Double.isFinite(maxProbability) || minProbability<0 ||
           maxProbability>1 || minProbability>maxProbability || !Double.isFinite(noveltyThreshold) ||
           noveltyThreshold<0 || noveltyThreshold>1 || !Double.isFinite(gainThreshold) || gainThreshold<0)
            throw new IllegalArgumentException("Invalid onlineControl options");
        if("replay".equals(mode) && (replayFile==null || replayFile.trim().isEmpty()))
            throw new IllegalArgumentException("replay mode requires onlineControl.replayFile");
    }
}

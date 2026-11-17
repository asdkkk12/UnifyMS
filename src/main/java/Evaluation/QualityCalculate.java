package Evaluation;

import GA.DiagramParser;

import java.util.*;

public class QualityCalculate {


    //计算实体属于哪个微服务
    public static Map<Integer, Integer> ContexttoEntity(ArrayList<double[]> UCtoE, ArrayList<ArrayList<Integer>> divi){
        HashMap<Integer, Integer> M = new HashMap<>();
        int temp=0;
        for (int i=0 ;i<UCtoE.get(0).length ;i++){
            int val=0,cnt=0,pos=0 ;
            for (ArrayList<Integer> cluster:divi){
                temp=0;
                for (Integer point : cluster) {
                    if(UCtoE.get(point)[i] == 1){//可能存在问题
                        temp++;
                    }
                }
                if(temp >= val){
                    val = temp;
                    pos = cnt;
                }
                cnt++;
            }
            M.put(i,pos);
        }
        return M;
    }



// Paper equations (5)-(21). The shared evaluator also caches repeated partitions.
    public static double functionCoupling(ArrayList<ArrayList<Integer>> divi, ArrayList<ArrayList<Integer>> Edivi) {
        return PaperMetrics.evaluate(divi,Edivi)[0];
    }
    public static double structureCohesion(ArrayList<ArrayList<Integer>> divi) {
        return PaperMetrics.useCaseMeans(divi)[0];
    }
    public static double structureCoupling(ArrayList<ArrayList<Integer>> divi) {
        return PaperMetrics.useCaseMeans(divi)[1];
    }
    public static double functionalCohesion(ArrayList<ArrayList<Integer>> divi, ArrayList<ArrayList<Integer>> Edivi) {
        return PaperMetrics.evaluate(divi,Edivi)[1];
    }
    // Preserve the historical public spelling for existing callers.
    public static double Modilarity(ArrayList<ArrayList<Integer>> divi, ArrayList<ArrayList<Integer>> Edivi) {
        return PaperMetrics.evaluate(divi,Edivi)[2];
    }

    public static int[][][] generateInterface(ArrayList<ArrayList<Integer>>divi, ArrayList<ArrayList<Integer>>Edivi){
        Map<Integer, Integer> M = new HashMap<>();
        ArrayList<double[]>UCtoE = DiagramParser.instancegetUCtoE;
        ArrayList<double[]>EtoE = DiagramParser.instancegetEtoE;

        int Mcnt = 0;
        for (ArrayList<Integer> cluster:Edivi){
            for (Integer point:cluster){
                M.put(point,Mcnt);
            }
            Mcnt++;
        }

        int flag[][][] = new int[2][divi.size()][EtoE.size()];
        // for(int i=0;i< EtoE.size();i++)
        //     flag[i]=0;

        for (int i = 0 ; i< EtoE.toArray().length; i++){  //断开关系
            for (int j = 0 ; j<EtoE.toArray().length ; j++){
                if (EtoE.get(i)[j] == 1){
                    if (!M.containsKey(i) ||!M.containsKey(j))
                        continue;
                    int need = M.get(i);
                    int needed = M.get(j);
                    if(!M.get(i).equals(M.get(j))){
                        flag[0][need][j] = 1;
                        flag[1][needed][j] = 1;
                    }
                }
            }
        }


        for (int i = 0; i < divi.size(); i++) {
            for (int j = 0; j < divi.get(i).size(); j++) {
                int IDofUC = divi.get(i).get(j);
                for (int k = 0; k < EtoE.size(); k++) {
                    if (UCtoE.get(IDofUC)[k]==1 && M.containsKey(k) && M.get(k) != i){
                        flag[0][i][k] = 1;
                        flag[1][M.get(k)][k] = 1;
                    }
                }
            }
        }
        return flag;
    }

    public static double Instability(ArrayList<ArrayList<Integer>> divi, ArrayList<ArrayList<Integer>> Edivi) {
        return PaperMetrics.evaluate(divi,Edivi)[3];
    }

    public double calServiceGranularity(ArrayList<double[]> UCtoE, ArrayList<double[]>EtoE, ArrayList<ArrayList<Integer>>divi){
        Map<Integer, Integer> M = ContexttoEntity(UCtoE,divi);
        double cnt = 0;
        for (int i = 0 ; i< EtoE.toArray().length; i++){
            for (int j=0; j<EtoE.toArray().length ;j++){
                if (EtoE.get(i)[j] == 1){
                    if(M.get(i) == M.get(j)){
                        cnt = cnt+1;
                    }
                }
            }
        }
        return cnt/(1.0*divi.size());
    }

    public double calCohesion(ArrayList<double[]> UCtoE, ArrayList<double[]>EtoE, ArrayList<ArrayList<Integer>>divi){
        Map<Integer, Integer> M = ContexttoEntity(UCtoE,divi);
        double result =0;
        for (int i = 0 ; i<divi.size() ;i++){
            int entityNum = EtoE.size();
            int cnt = 0;
            for (int j =0 ; j<entityNum ;j++){
                if(M.get(j) == i){
                    cnt++;
                }
            }
            result+= cnt * divi.get(i).size();
        }
        return result / divi.size();
    }

    public double calDesignSize(ArrayList<double[]> UCtoE, ArrayList<double[]>EtoE, ArrayList<ArrayList<Integer>>divi){
        return divi.size()/(1.0*UCtoE.size());
    }
/*
    public void judge(ArrayList<double[]> UCtoE, ArrayList<double[]>EtoE, ArrayList<ArrayList<Integer>>divi,ArrayList<double[]> dataSet){
       // double x1 = functionCoupling(UCtoE, EtoE, divi);
        double x2 = calComplexity(divi, dataSet);
        double x3 = calCohesion(UCtoE, EtoE, divi);
        double x4 = calServiceGranularity(UCtoE, EtoE, divi);
        double x5 = calDesignSize(UCtoE, EtoE, divi);
        //System.out.println("Coupling = "+x1);
        //System.out.println("Complexity = "+x2);
        //System.out.println("Cohesion = "+x3);
        //System.out.println("ServiceGranularity = "+x4);
        //System.out.println("DesignSize = "+x5);
        double effective = 0.5*x3 + 0.5*x4;
        double flexibility = 1.5*x4 -0.5*x1;
        double Reusability =  -0.22*x1 + 0.61*x3 +0.61*x4;
        double Understandability = -0.66*x1 +0.5*x3 - 0.66*x2 -0.66*x5 +0.5*x4;
        double Quallity = (effective+flexibility+Reusability+Understandability)/4;
        System.out.println("Quality = "+Quallity);
    }

 */
}

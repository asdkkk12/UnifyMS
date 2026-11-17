package GPTenhancing.chat;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import GPTenhancing.dto.Message;
import GPTenhancing.dto.RequestBody;
import GPTenhancing.dto.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

//超市管理系统
//去掉财务人员和库存管理人员以及客服人员
public class Main {
    public static final String URL ="https://api.openai.com/v1/chat/completions";
    public static final String API_KEY = System.getenv("OPENAI_API_KEY");
    public static String RE=" ";
    public static List<Message> list = new ArrayList<>();
    @SneakyThrows
    public static void main(String[] args) {

        //确认参与人员
        System.out.print("请问您想要绘制什么软件的需求？\n");

        //用户故事需求确认。
        Scanner sc = new Scanner(System.in);
        String str = sc.next();
        String Acter = "\n。请向我提供可能的参与人员。（各个参与人员用，隔开保持在4个）\n";
        String strus = str + Acter;

        RE = chat(strus);
        System.out.println(RE);
        System.out.print("是否有补充或修改？如果不需要我们将向您提供参与者可能的需求。\n");

        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + "\n。请向我提供可能的参与人员。（各个参与人员用，隔开）\n" ;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("是否有补充或修改？如果不需要我们将向您提供参与者可能的需求。\n");
        }
//结束确认参与人员

        //确认用户需求
        String reK = "\n请分别给出他们可能的需求，尽量保持在1个需求。（请用标号1、分开这些需求）\n";
        String strus1 = str + "中有" + RE + reK;

        RE = chat(strus1);
        System.out.println(RE);
        System.out.print("\n是否有补充或修改？如果不需要我们将其写成用户故事的形式。\n");

        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + reK;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("是否有补充或修改？如果不需要我们将其写成用户故事的形式。\n");
        }
//结束确认用户需求
        //确认用户故事
        String ucK = "\n请将以上需求编写成用户故事。（请用标号1、分别标记这些需求，不区分参与人员，去掉用户把序号连在一起）\n";
        String strus2 = str + "中有" + RE + ucK;
        RE = chat(strus2);


        System.out.println(RE);
        System.out.print("\n请问是否有需要补充的需求信息？如果不需要我们将会提供前置条件、后置条件。\n\n");

        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + ucK;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("\n请问是否有需要补充的需求信息？如果不需要我们将会提供前置条件、后置条件\n");
        }
        //结束确认用户故事
        //确认前置条件、后置条件

        String UserStories =RE;
        String qhK = "\n请给出以上用户故事的前置条件、后置条件\n";
        String strus4 = str +"中有" + UserStories + qhK;
        RE = chat(strus4);


        System.out.println(RE);
        System.out.print("\n是否有补充或修改？如果不需要我们将向您提供基本流程。\n");

        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + ucK;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("\n是否有补充或修改？如果不需要我们将向您提供基本流程。\n");
        }
        //结束确认前置条件、后置条件
        //确认基本条件
        String JB = "\n请将以上用户需求，进行扩展其模板如下：\n";
        String JB2 = "作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                "{ \n" +
                "基本流程 [当前用户故事的基本执行流程] {\n" +
                "\t\t（用户） 1、 [用户] 应 [行为]。(循环 1 ~ 3 直到输入结束)\n" +
                "\t\t（用户） 2、 [用户] 应 [行为]。\n" +
                "\t\t（系统） 3、 [系统] 应 [行为]。\n" +
                "（用户） 4、 [用户] 应 [行为]。（选择 a4，b4，c4，d4）\n" +
                "    }";
        String JB1 = UserStories + JB + JB2;
        RE = chat(JB1);
        String JB3 = RE + "\n请在基本流程中加入系统反馈。\n";
        RE = chat(JB3);
        String JB4 = RE + "\n请为每一个用户故事配对应基本流程。\n";
        RE = chat(JB4);
        System.out.println(RE);
        System.out.print("\n请问是否有需要修改的需求信息？如果不需要我们将会提供扩展流程。\n");


        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("请问是否有需要补充的需求信息？如果不需要我们将会提供扩展流程。\n");
        }
//结束确认基本条件
        //确认扩展流程

        String KZ = ("作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                "扩展流程 [当前用户故事除基本流程意外的其他情况，例如可选流程、异常处理流程] {{\n" +
                "\t\t（可选）*a、 经理在任意时刻，需要进行超控操作：\n" +
                "\t\t\t（输入）1、  系统进入经理授权模式。\n" +
                "\t\t\t（输入）2、  经理或收银员执行某一经理模式的操作。\n" +
                "\t\t（异常处理）*b、 系统在任意时刻，失败：\n" +
                "\t\t\t（输入）1、 收银员重启系统，登录，请求恢复上次状态。\n" +
                "\t\t\t（输出）2、 系统重建上次状态。\n" +
                "\t\t（可选）2-3a、 顾客告诉收银员其免税状况（例如，年长者本国人等）：\n" +
                "\t\t\t（输入）1、 收银员进行核实，并输入免税状况编码。\n" +
                "\t\t\t（输出）2、 系统记录读状况编码（在计算税金时使用）。\n" +
                "\t\t（选择）4a、 现金支付：\n" +
                "\t\t\t（输入）1、 收银员输入收取的现金额。\n" +
                "\t\t\t（输出）2、 系统显示找零金额，并弹出现金抽屉。\n" +
                "\t\t（选择）4b、 信用卡支付：\n" +
                "\t\t\t（输入）1、 顾客输入信用卡账户信息。\n" +
                "\t\t\t（输出）2、 系统显示其支付信息以备验证。\n" +
                "}");
        String KZ2 = ("\n将以上需求编写为以下模板的形式\n");
        String KZ1 = UserStories + KZ2 + KZ;
        RE = chat(KZ1);
        System.out.println(RE);
        System.out.print("\n请问是否有需要修改的需求信息。\n");
        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("以退出程序");
                break;
            }

            String US = RE + usExten;
            String content3 = chat(US);
            System.out.println(content3);
            RE = content3;
            System.out.print("请问是否有需要修改的需求信息。\n");
        }
        //结束确认扩展流程
        /*while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + ucK;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("\n请问是否有需要补充的需求信息？如果不需要我们将会提供前置条件、后置条件\n");
        }
        String REUS = RE;

        List<String> UserStories = extractSentences(REUS);

        String PrePostCond = null;

        for (String UserStorie : UserStories) {

            String qhK = "\n请给出以上用户故事的前置条件、后置条件（只需要各一个）\n";
            String strus4 = UserStorie + qhK;
            //Thread.sleep(100);
            RE = chat(strus4);
            PrePostCond = PrePostCond + "\n" + RE;

            System.out.println(RE);

        }
        System.out.print("\n是否有补充或修改？如果不需要我们将向您提供基本流程。\n");

        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }
            String qhK = "\n请给出以上用户故事的前置条件、后置条件（只需要各一个）\n";
            String US = PrePostCond + usExten + qhK;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("\n是否有补充或修改？如果不需要我们将向您提供基本流程。\n");
        }

        String BasicProcess = null;
        List<String> Basics = null;
        int i = 0;

        for (String UserStorie : UserStories) {

            String JB = "\n请将以上用户需求，进行扩展其模板如下：\n";
            String JB2 = "作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                    "{ \n" +
                    "基本流程 [当前用户故事的基本执行流程] {\n" +
                    "\t\t（用户） 1、 [用户] 应 [行为]。(循环 1 ~ 3 直到输入结束)\n" +
                    "\t\t（用户） 2、 [用户] 应 [行为]。\n" +
                    "\t\t（系统） 3、 [系统] 应 [行为]。\n" +
                    "（用户） 4、 [用户] 应 [行为]。（选择 a4，b4，c4，d4）\n" +
                    "    }";
            String strus4 = UserStorie + JB + JB2;
            RE = chat(strus4);
            BasicProcess = BasicProcess + "\n" + RE;
            Basics.set(i, RE);
            i++;
            System.out.println(RE);

        }
        System.out.print("\n是否有补充或修改？如果不需要我们将向您提供扩展流程。\n");


        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten;
            String content3 = chat(BasicProcess);
            System.out.println(content3);
            RE = content3;
            System.out.print("请问是否有需要修改的需求信息？如果不需要我们将会提供扩展流程。\n");
        }
        String ExtendedProcess = null;
        List<String> Extendeds = null;
        int j = 0;

        for (String Basic : Basics) {

            String KZ = ("作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                    "扩展流程 [当前用户故事除基本流程意外的其他情况，例如可选流程、异常处理流程] {{\n" +
                    "\t\t（可选）*a、 经理在任意时刻，需要进行超控操作：\n" +
                    "\t\t\t（输入）1、  系统进入经理授权模式。\n" +
                    "\t\t\t（输入）2、  经理或收银员执行某一经理模式的操作。\n" +
                    "\t\t（异常处理）*b、 系统在任意时刻，失败：\n" +
                    "\t\t\t（输入）1、 收银员重启系统，登录，请求恢复上次状态。\n" +
                    "\t\t\t（输出）2、 系统重建上次状态。\n" +
                    "\t\t（可选）2-3a、 顾客告诉收银员其免税状况（例如，年长者本国人等）：\n" +
                    "\t\t\t（输入）1、 收银员进行核实，并输入免税状况编码。\n" +
                    "\t\t\t（输出）2、 系统记录读状况编码（在计算税金时使用）。\n" +
                    "\t\t（选择）4a、 现金支付：\n" +
                    "\t\t\t（输入）1、 收银员输入收取的现金额。\n" +
                    "\t\t\t（输出）2、 系统显示找零金额，并弹出现金抽屉。\n" +
                    "\t\t（选择）4b、 信用卡支付：\n" +
                    "\t\t\t（输入）1、 顾客输入信用卡账户信息。\n" +
                    "\t\t\t（输出）2、 系统显示其支付信息以备验证。\n" +
                    "}");
            String KZ2 = ("\n将以上需求编写为以下模板的形式\n");
            String KZ1 = Basic + KZ2 + KZ;
            RE = chat(KZ1);
            ExtendedProcess = ExtendedProcess + "\n" + RE;
            Basics.set(i, RE);
            System.out.println(RE);

        }
        System.out.print("\n是否有补充或修改？如果不需要我们将向您提供扩展流程。\n");


        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("以退出程序");
                break;
            }

            String US = ExtendedProcess + usExten;
            String content3 = chat(US);
            System.out.println(content3);
            RE = content3;
            System.out.print("请问是否有需要修改的需求信息。\n");
        }*/
    }


    public static String chat (String str) {
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "7890");
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7890");
        Message message = new Message();
        message.setRole("user");
        message.setContent(str);

        list.add(message);

        RequestBody requestBody = new RequestBody();
        requestBody.setModel("gpt-3.5-turbo");
        requestBody.setMessages(list);

        String data = JSON.toJSONString(requestBody);
        while(true){
            String resJSON = HttpRequest.post(URL)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .body(data)
                    //.timeout(20000)
                    .execute()
                    .body();

            if (resJSON == null || resJSON.length()==0)
                continue;
            ResponseBody reposeBody = JSON.parseObject(resJSON, ResponseBody.class);
            String content =  reposeBody.getChoices().get(0).getMessage().getContent();
            return content;
        }
    }


    public static List<String> extractSentences(String input) {
        List<String> sentences = new ArrayList<>();
        String[] lines = input.split("\n");
        for (String line : lines) {
            if (line.length()==0)
                continue;
        //    int pos;
       //     for (pos = 0; pos < line.length(); pos++) {
         //       if (line.indexOf(pos)=='、');
       //     }
            line = line.trim();
            String linetemp = line.substring(line.length());
            sentences.add(linetemp);
//            if (line.startsWith("、") && line.endsWith("。")) {
//                sentences.add(linetemp);
//            }
        }

        return sentences;
    }
}

/*String qhK = "\n请给出以上用户故事的前置条件、后置条件\n";
        String strus4 = str +"中有" + RE + qhK;
        RE = chat(strus4);


        System.out.println(RE);
        System.out.print("\n是否有补充或修改？如果不需要我们将向您提供基本流程。\n");

        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + ucK;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("\n是否有补充或修改？如果不需要我们将向您提供基本流程。\n");
        }

        String JB = "\n请将以上用户需求，进行扩展其模板如下：\n";
        String JB2 = "作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                "{ \n" +
                "基本流程 [当前用户故事的基本执行流程] {\n" +
                "\t\t（用户） 1、 [用户] 应 [行为]。(循环 1 ~ 3 直到输入结束)\n" +
                "\t\t（用户） 2、 [用户] 应 [行为]。\n" +
                "\t\t（系统） 3、 [系统] 应 [行为]。\n" +
                "（用户） 4、 [用户] 应 [行为]。（选择 a4，b4，c4，d4）\n" +
                "    }";
        String JB1 = RE + JB + JB2;
        RE = chat(JB1);
        //String JB3 = RE + "\n请在基本流程中加入系统反馈。\n";
        //RE = chat(JB3);
        String JB4 = RE + "\n请为每一个用户故事配对应基本流程。\n";
        RE = chat(JB4);
        System.out.println(RE);
        System.out.print("\n请问是否有需要修改的需求信息？如果不需要我们将会提供扩展流程。\n");*/







    /*    while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten + reus;
            String content2 = chat(US);
            System.out.println(content2);
            RE = content2;
            System.out.print("请问是否有需要补充的需求信息？如果不需要我们将会提供前置条件、后置条件以及基本流程。\n");
        }

        String JB = "\n请将以上用户需求，进行扩展其模板如下：\n";
        String JB2 = "作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                "{ \n" +
                "基本流程 [当前用户故事的基本执行流程] {\n" +
                "\t\t（用户） 1、 [用户] 应 [行为]。(循环 1 ~ 3 直到输入结束)\n" +
                "\t\t（用户） 2、 [用户] 应 [行为]。\n" +
                "\t\t（系统） 3、 [系统] 应 [行为]。\n" +
                "（用户） 4、 [用户] 应 [行为]。（选择 a4，b4，c4，d4）\n" +
                "    }";
        String JB1 = RE + JB + JB2;
        RE = chat(JB1);
        String JB3 = RE + "\n请在基本流程中加入系统反馈。\n";
        RE = chat(JB3);
        String JB4 = RE + "\n请为每一个用户故事配对应的前置条件、后置条件、基本流程。\n";
        RE = chat(JB4);
        System.out.println(RE);
        System.out.print("\n请问是否有需要修改的需求信息？如果不需要我们将会提供扩展流程。\n");
        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("请稍等....\n");
                break;
            }

            String US = RE + usExten;
            String content3 = chat(US);
            System.out.println(content3);
            RE = content3;
            System.out.print("请问是否有需要修改的需求信息？如果不需要我们将会提供扩展流程。\n");
        }

        String KZ = ("作为一个[用户]， 我想要[行为]， 以便于[目的]\n" +
                "扩展流程 [当前用户故事除基本流程意外的其他情况，例如可选流程、异常处理流程] {{\n" +
                "\t\t（可选）*a、 经理在任意时刻，需要进行超控操作：\n" +
                "\t\t\t（输入）1、  系统进入经理授权模式。\n" +
                "\t\t\t（输入）2、  经理或收银员执行某一经理模式的操作。\n" +
                "\t\t（异常处理）*b、 系统在任意时刻，失败：\n" +
                "\t\t\t（输入）1、 收银员重启系统，登录，请求恢复上次状态。\n" +
                "\t\t\t（输出）2、 系统重建上次状态。\n" +
                "\t\t（可选）2-3a、 顾客告诉收银员其免税状况（例如，年长者本国人等）：\n" +
                "\t\t\t（输入）1、 收银员进行核实，并输入免税状况编码。\n" +
                "\t\t\t（输出）2、 系统记录读状况编码（在计算税金时使用）。\n" +
                "\t\t（选择）4a、 现金支付：\n" +
                "\t\t\t（输入）1、 收银员输入收取的现金额。\n" +
                "\t\t\t（输出）2、 系统显示找零金额，并弹出现金抽屉。\n" +
                "\t\t（选择）4b、 信用卡支付：\n" +
                "\t\t\t（输入）1、 顾客输入信用卡账户信息。\n" +
                "\t\t\t（输出）2、 系统显示其支付信息以备验证。\n" +
                "}");
        String KZ2 = ("\n将以上需求编写为以下模板的形式\n");
        String KZ1 = RE + KZ2 + KZ;
        RE = chat(KZ1);
        System.out.println(RE);
        System.out.print("\n请问是否有需要修改的需求信息。\n");
        while (true) {

            String usExten = sc.next();

            if ("不需要".equals(usExten)) {
                System.out.print("以退出程序");
                break;
            }

            String US = RE + usExten;
            String content3 = chat(US);
            System.out.println(content3);
            RE = content3;
            System.out.print("请问是否有需要修改的需求信息。\n");
        }*/


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

import static GPTenhancing.chat.Main.chat;

public class XMLreading {
    public static String RE=" ";
    public static final String URL ="https://api.openai.com/v1/chat/completions";
    public static final String API_KEY = System.getenv("OPENAI_API_KEY");
    public static void main(String[] args){
        String Acter = "\n我将输入一个\n";
        //return new int[12];
        RE = chat("a = 2");
        System.out.println(RE);
        RE = chat("a的值是多少");
        System.out.println(RE);
    }
}

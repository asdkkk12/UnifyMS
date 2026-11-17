package tuning;

import GA.remodelparse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemodelCommentTest {
    @TempDir Path temp;

    @Test void entityReferencesIgnoreLineAndBlockCommentsButKeepQuotedSlashes() throws Exception {
        Path input=temp.resolve("comments.remodel");
        String text="DomainModel Example {\n"
            +"// Entity Ghost {\n// }\n"
            +"/* Entity OtherGhost {\n} */\n"
            +"Entity First { // Entity InlineGhost {\n"
            +"@Description(\"https://example.test/a/*literal*/\") id : Integer\n"
            +"[Refer]\n// hidden : Ghost Association\n"
            +"/* hidden : OtherGhost Association */\n"
            +"other : Second Association\n}\nEntity Second {\nid : Integer\n}\n}\n";
        Files.write(input,text.getBytes(StandardCharsets.UTF_8));
        Map<String,Integer> entities=remodelparse.getEntityPos(input.toString());
        assertEquals(new HashSet<>(Arrays.asList("First","Second")),entities.keySet());
        assertEquals(1,remodelparse.getEtoE(input.toString()).get(entities.get("First"))[entities.get("Second")]);
    }
}

package tuning;

import GA.DiagramParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourceMetadataTest {
    @TempDir Path temp;

    @Test void astFieldsAreMetadataAndDoNotReplaceLegacyModel() throws Exception {
        Files.write(temp.resolve("SampleController.java"), ("public class SampleController {\n public Object browse() {\n return fooService.load();\n }\n}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(temp.resolve("FooService.java"), ("public class FooService {\n public Object load() {\n return bookMapper.findBooks();\n }\n}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(temp.resolve("Book.java"), ("@Data\npublic class Book {\n private Long id;\n private java.util.List<String> tags;\n private static final String secret = \"not-exported\";\n}\n").getBytes(StandardCharsets.UTF_8));
        ModelLoader.load(temp);
        String hash = FilesIO.inputHash(temp);
        Map<String,Object> structure = ModelLoader.structure();
        Map<String,Object> metadata = JavaSourceMetadata.inspect(temp);
        Map<?,?> schemas = (Map<?,?>) metadata.get("entitySchemas");
        Map<?,?> book = (Map<?,?>) schemas.get("Book");
        List<?> fields = (List<?>) book.get("attributes");
        assertEquals(2,fields.size());
        assertEquals("java.util.List<String>",((Map<?,?>)fields.get(1)).get("type"));
        assertFalse(metadata.toString().contains("not-exported"));
        assertEquals(structure,ModelLoader.structure());
        assertEquals(hash,FilesIO.inputHash(temp));
        assertEquals(1,DiagramParser.numofUC);
    }
}

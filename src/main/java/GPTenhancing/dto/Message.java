package GPTenhancing.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Message {
    private String role;
    private String content;

    public String getRole() { return role; }
    public void setRole(String value) { this.role = value; }
    public String getContent() { return content; }
    public void setContent(String value) { this.content = value; }
}

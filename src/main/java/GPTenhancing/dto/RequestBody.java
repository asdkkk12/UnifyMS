package GPTenhancing.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RequestBody {
    private String model;
    private List<Message> messages;
    public String getModel() { return model; }
    public void setModel(String value) { this.model = value; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> value) { this.messages = value; }
}


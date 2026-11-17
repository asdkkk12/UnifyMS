package GPTenhancing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Choices {
    private Message message;
    public Message getMessage() { return message; }
    public void setMessage(Message value) { this.message = value; }
}

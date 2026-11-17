package GPTenhancing.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseBody {
    private List<Choices> choices;
    public List<Choices> getChoices() { return choices; }
    public void setChoices(List<Choices> value) { this.choices = value; }
}

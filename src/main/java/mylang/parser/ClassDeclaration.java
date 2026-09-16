package mylang.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import mylang.Range;

public record ClassDeclaration(
    IdentifierDeclaration name, List<@NonNull BlockItem> members, Range range
) implements Declaration {
}

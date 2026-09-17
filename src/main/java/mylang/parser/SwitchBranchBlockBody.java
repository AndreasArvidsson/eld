package mylang.parser;

import mylang.Range;

public record SwitchBranchBlockBody(BlockStatement block, Range range)
    implements SwitchBranchBody {
}

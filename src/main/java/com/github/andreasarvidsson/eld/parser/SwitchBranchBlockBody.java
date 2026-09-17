package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record SwitchBranchBlockBody(BlockStatement block, Range range)
    implements SwitchBranchBody {
}

package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record SwitchBranchBlockBody(BlockStatement block)
    implements SwitchBranchBody {
    @Override
    public Range range() {
        return block.range();
    }
}

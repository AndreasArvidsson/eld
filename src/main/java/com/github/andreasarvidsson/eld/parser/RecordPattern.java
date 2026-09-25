package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record RecordPattern(List<RecordPatternField> fields, Range range)
    implements Pattern {
}

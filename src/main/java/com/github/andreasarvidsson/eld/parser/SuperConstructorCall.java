package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record SuperConstructorCall(
    List<@NonNull Expression> arguments, Range range
) implements Statement {
}

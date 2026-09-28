package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record DiscardDeclaration(Range range) implements BindingDeclaration {
}

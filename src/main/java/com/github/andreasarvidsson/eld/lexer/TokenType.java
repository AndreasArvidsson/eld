package com.github.andreasarvidsson.eld.lexer;

public enum TokenType {
    // Identifiers and literals
    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    CHAR_LITERAL,
    STRING_LITERAL,
    RAW_STRING_LITERAL,
    FORMAT_STRING_START,
    FORMAT_STRING_END,
    BOOLEAN_LITERAL,

    // Keywords
    CONST,
    VAR,
    CLASS,
    EXTENDS,
    INTERFACE,
    IMPLEMENTS,
    NEW,
    CONSTRUCTOR,
    THIS,
    SUPER,
    FUNC,
    PUBLIC,
    PROTECTED,
    RETURN,
    TRY,
    CATCH,
    FINALLY,
    THROW,
    YIELD,
    IF,
    SWITCH,
    CASE,
    ELIF,
    ELSE,
    DO,
    WHILE,
    FOR,
    NULL,
    BREAK,
    CONTINUE,

    // Operators
    PLUS,
    PLUS_PLUS,
    MINUS,
    MINUS_MINUS,
    STAR,
    SLASH,
    PERCENT,

    EQUAL,
    EQUAL_EQUAL,
    BANG,
    BANG_EQUAL,

    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,

    AND,
    OR,

    ARROW,

    // Punctuation
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COLON,
    DOT,
    ELLIPSIS,
    QUESTION,
    SEMICOLON,
    COMMA,
    PIPE,

    EOF
}

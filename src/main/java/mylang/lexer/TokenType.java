package mylang.lexer;

public enum TokenType {
    // Identifiers and literals
    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    CHAR_LITERAL,
    STRING_LITERAL,
    BOOLEAN_LITERAL,

    // Keywords
    CONST,
    VAR,
    FUNC,
    RETURN,
    IF,
    ELIF,
    ELSE,
    WHILE,
    FOR,

    // Operators
    PLUS,
    MINUS,
    STAR,
    SLASH,

    EQUAL,
    EQUAL_EQUAL,
    BANG,
    BANG_EQUAL,

    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,

    FAT_ARROW,

    // Punctuation
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COLON,
    COMMA,

    EOF
}

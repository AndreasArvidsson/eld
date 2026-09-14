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

    FAT_ARROW,

    // Punctuation
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACE,
    RIGHT_BRACE,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COLON,
    SEMICOLON,
    COMMA,

    EOF
}

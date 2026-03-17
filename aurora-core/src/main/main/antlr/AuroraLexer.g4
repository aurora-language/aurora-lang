lexer grammar AuroraLexer;

@lexer::members {
    private int commentDepth = 0;
}

// --------------------------
// Keywords (v1.1 specification section 2.3)
// --------------------------

// Control Flow (8)
ASYNC       : 'async';
AWAIT       : 'await';
BREAK       : 'break';
CATCH       : 'catch';
CONTINUE    : 'continue';
DEFAULT     : 'default';
DO          : 'do';
ELSE        : 'else';
ELSEIF      : 'elseif';
IF          : 'if';
FOR         : 'for';
MATCH       : 'match';
REPEAT      : 'repeat';
RETURN      : 'return';
THROW       : 'throw';
TRY         : 'try';
UNTIL       : 'until';
WHILE       : 'while';
NONNULL     : 'nonnull';

// Class & Object Declaration (9)
CLASS       : 'class';
CONSTRUCTOR : 'constructor';
ENUM        : 'enum';
OVERRIDE    : 'override';
RECORD      : 'record';
STATIC      : 'static';
SUPER       : 'super';
TRAIT       : 'trait';
TYPE        : 'type';
NATIVE      : 'native';

// Type Keywords (9)
BOOL        : 'bool';
DOUBLE      : 'double';
FLOAT       : 'float';
INT         : 'int';
LONG        : 'long';
NONE        : 'none';
OBJECT      : 'object';
STRING      : 'string';
VOID        : 'void';
VARARGS     : 'varargs';

// Visibility & Modifiers (6)
LOCAL       : 'local';
PROTECTED   : 'protected';
PUB         : 'pub';
VAL         : 'val';
VAR         : 'var';

// Keywords (other 7)
ABSTRACT    : 'abstract';
FUN         : 'fun';
IN          : 'in';
OUT         : 'out';
NULL        : 'null';
SELF        : 'self';
THREAD      : 'thread';
TRUE        : 'true';
FALSE       : 'false';
USING       : 'use';
PACKAGE     : 'namespace';
FINALLY     : 'finally';

// Type Checking & Casting
AS          : 'as';
IS          : 'is';

// --------------------------
// Literals
// --------------------------

// Numeric Literals (v1.1 section 3.1.1)
INTEGER_LITERAL
    : [0-9] ('_'? [0-9]+)*                          // Decimal: 1_000_000
    | '0x' [0-9a-fA-F] ('_'? [0-9a-fA-F]+)*         // Hex: 0xDEAD_BEEF
    | '0b' [01] ('_'? [01]+)*                       // Binary: 0b1100_0011
    | '0o' [0-7] ('_'? [0-7]+)*                     // Octal: 0o755
    ;

LONG_LITERAL
    : INTEGER_LITERAL [lL]
    ;

fragment FLOATING_POINT_LITERAL
    : [0-9]+ ('_'? [0-9]+)*
      ('.' [0-9]+ ('_'? [0-9]+)*)?
      ([eE] [+-]? [0-9]+ ('_'? [0-9]+)*)?
    | '.' [0-9]+ ('_'? [0-9]+)*
      ([eE] [+-]? [0-9]+ ('_'? [0-9]+)*)?
    | [0-9]+ ([eE] [+-]? [0-9]+ ('_'? [0-9]+)*)
    ;

DOUBLE_LITERAL
    : FLOATING_POINT_LITERAL [dD]?
    ;

FLOAT_LITERAL
    : FLOATING_POINT_LITERAL [fF]?
    ;

// String Literals (v1.1 section 3.1.2)
STRING_LITERAL
    : '"' ( ~["\\\r\n] | ESCAPE_SEQUENCE | STRING_INTERPOLATION )* '"'
    | '"""' ( . | '\r' | '\n' )*? '"""'
    ;

fragment ESCAPE_SEQUENCE
    : '\\' ( [\\"/bfnrt] | 'u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT )
    ;

fragment HEX_DIGIT
    : [0-9a-fA-F]
    ;

fragment STRING_INTERPOLATION
    : '${' ( ~[}] | '\\}' )* '}'
    ;

// Identifiers (v1.1 section 2.4)
IDENTIFIER
    : [a-zA-Z_] [a-zA-Z0-9_]*
    ;

// --------------------------
// Operators & Delimiters (v1.1 section 5 & 17.1)
// --------------------------

// Member Access & Safe Operations
DOT          : '.';
QUESTION_DOT : '?.';
BANG_BANG    : '!!';
COLON_COLON  : '::';

// Ranges (v1.1 section 5.5)
RANGE_EXCL   : '..';
RANGE_INCL   : '..=';

// Arithmetic Operators (v1.1 section 5.1)
PLUS         : '+';
MINUS        : '-';
STAR         : '*';
SLASH        : '/';
PERCENT      : '%';

// Comparison Operators (v1.1 section 5.2)
EQ           : '==';
NEQ          : '!=';
LT           : '<';
GT           : '>';
LE           : '<=';
GE           : '>=';

// Logical Operators (v1.1 section 5.3)
AND          : '&&';
OR           : '||';
NOT          : '!';

// Special Operators (v1.1 section 5.5)
QUESTION     : '?';
ELVIS        : '?:';

// Assignment Operators (v1.1 section 5.4)
ASSIGN       : '=';
PLUS_ASSIGN  : '+=';
MINUS_ASSIGN : '-=';
STAR_ASSIGN  : '*=';
SLASH_ASSIGN : '/=';
PERCENT_ASSIGN : '%=';

// Delimiters
LPAREN       : '(';
RPAREN       : ')';
LBRACE       : '{';
RBRACE       : '}';
LBRACK       : '[';
RBRACK       : ']';
COMMA        : ',';
SEMICOLON    : ';';
COLON        : ':';

// Arrow Operators
ARROW        : '->';
FAT_ARROW    : '=>';

// --------------------------
// Whitespace
// --------------------------

WS : [ \t\r\n\u000C]+ -> skip;

// --------------------------
// Comments (v1.1 section 2.2)
// --------------------------

LINE_COMMENT
    : '//' ~[\r\n]*
    -> channel(HIDDEN)
    ;

DOC_COMMENT
    : '/**' ( . | '\r' | '\n' )*? '*/'
    -> channel(HIDDEN)
    ;

BLOCK_COMMENT_START
    : '/*' 
    { commentDepth++; }
    -> pushMode(BLOCK_COMMENT_MODE), skip
    ;

mode BLOCK_COMMENT_MODE;

BLOCK_COMMENT_NESTED_START
    : '/*'
    { commentDepth++; }
    -> skip
    ;

BLOCK_COMMENT_END
    : '*/'
    {
        commentDepth--;
        if (commentDepth == 0) {
            popMode();
        }
    }
    -> skip
    ;

BLOCK_COMMENT_CHAR
    : . 
    -> skip
    ;
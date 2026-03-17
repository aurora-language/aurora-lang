parser grammar AuroraParser;

options {
    tokenVocab = AuroraLexer;
}

// ==========================
// Compilation Unit
// ==========================

compilationUnit
    : packageDeclaration? importDeclaration* topLevelElement* EOF
    ;

packageDeclaration
    : PACKAGE importPath SEMICOLON?
    ;

// Import Declarations (v1.1 section 13.3)
importDeclaration
    : USING importPath (AS IDENTIFIER)? SEMICOLON?                      # SingleImport
    | USING importPath DOT STAR SEMICOLON?                              # WildcardImport
    | USING importPath DOT LBRACE importMember (COMMA importMember)* RBRACE SEMICOLON?  # MultiImport
    ;

importPath
    : IDENTIFIER (DOT IDENTIFIER)*
    ;

importMember
    : IDENTIFIER (AS IDENTIFIER)?
    ;

qualifiedName
    : IDENTIFIER (DOT IDENTIFIER)*
    ;

// Top-Level Elements
topLevelElement
    : topLevelDeclaration
    | statement
    ;

topLevelDeclaration
    : classDeclaration
    | interfaceDeclaration
    | enumDeclaration
    | recordDeclaration
    | functionDeclaration
    | threadDeclaration
    | fieldDeclaration
    ;

// ==========================
// Class Declaration
// ==========================

classDeclaration
    : visibility? ABSTRACT? (CLASS | OBJECT) IDENTIFIER (LPAREN classParameterList RPAREN)? genericParameters? inheritance? classBody
    ;

classBody
    : LBRACE classMember* RBRACE
    ;

classMember
    : fieldDeclaration
    | constructorDeclaration
    | functionDeclaration
    | initializerBlock
    ;

fieldDeclaration
    : visibility? STATIC? (VAL | VAR) variableDeclaration (COMMA variableDeclaration)* SEMICOLON?
    ;

variableDeclaration
    : IDENTIFIER (COLON typeType)? (ASSIGN expression)?
    ;

constructorDeclaration
    : visibility? CONSTRUCTOR LPAREN parameterList? RPAREN block
    ;

initializerBlock
    : STATIC block
    ;

// ==========================
// Record Declaration (v1.1 section 9)
// ==========================

recordDeclaration
    : visibility? RECORD IDENTIFIER LPAREN classParameterList? RPAREN inheritance? LBRACE recordMember* RBRACE
    ;

classParameterList
    : classParameter (COMMA classParameter)*
    ;

classParameter
    : (VAL | VAR)? IDENTIFIER COLON typeType (ASSIGN expression)?
    | (VAL | VAR)? IDENTIFIER (COLON typeType)? ASSIGN expression
    ;

recordMember
    : functionDeclaration
    | fieldDeclaration
    ;

// ==========================
// Trait Declaration
// ==========================

interfaceDeclaration
    : visibility? TRAIT IDENTIFIER genericParameters? inheritance? interfaceBody
    ;

interfaceBody
    : LBRACE interfaceMember* RBRACE
    ;

interfaceMember
    : methodSignature SEMICOLON?
    | fieldDeclaration
    ;

methodSignature
    : visibility? ASYNC? FUN (typeType DOT)? IDENTIFIER genericParameters? LPAREN parameterList? RPAREN
      (ARROW typeType)?
    ;

// ==========================
// Enum Declaration
// ==========================

enumDeclaration
    : visibility? ENUM IDENTIFIER LBRACE enumMember (COMMA enumMember)* COMMA? RBRACE
    ;

enumMember
    : IDENTIFIER (ASSIGN expression)? (LBRACE enumMemberBody RBRACE)?
    ;

enumMemberBody
    : (fieldDeclaration | functionDeclaration)*
    ;

// ==========================
// Function Declaration
// ==========================

functionDeclaration
    : visibility? functionModifier* FUN (typeType DOT)? IDENTIFIER genericParameters? LPAREN parameterList? RPAREN (ARROW typeType)? (functionBody | FAT_ARROW expression)?
    ;

functionModifier
    : ASYNC | OVERRIDE | STATIC | ABSTRACT | NATIVE
    ;

functionBody
    : block
    | FAT_ARROW expression SEMICOLON?
    ;

parameterList
    : parameter (COMMA parameter)*
    ;

parameter
    : VARARGS? IDENTIFIER COLON typeType (ASSIGN expression)?
    | VARARGS? IDENTIFIER (COLON typeType)? ASSIGN expression  // Named parameter with default
    ;

// ==========================
// Thread Declaration (v1.1 section 12.1 - replaces go)
// ==========================

threadDeclaration
    : THREAD IDENTIFIER (LPAREN parameterList? RPAREN)? block
    ;

// ==========================
// Statements (v1.1 section 6)
// ==========================

statement
    : (VAL | VAR) variableDeclaration (COMMA variableDeclaration)* SEMICOLON?  # LocalVarDecl
    | expression SEMICOLON?                                                           # ExprStmt
    | IF expression exprBlock elseifClause* elseClause?                                   # IfStmt
    | MATCH expression LBRACE matchCase+ RBRACE                                       # MatchStmt
    | FOR IDENTIFIER IN expression block                                              # ForInStmt
    | WHILE expression block                                                          # WhileStmt
    | REPEAT block UNTIL expression SEMICOLON?                                        # RepeatUntilStmt
    | BREAK labelRef? SEMICOLON?                                                      # BreakStmt
    | CONTINUE labelRef? SEMICOLON?                                                   # ContinueStmt
    | RETURN expression? SEMICOLON?                                                   # ReturnStmt
    | THROW expression SEMICOLON?                                                     # ThrowStmt
    | TRY block catchClause+ finallyClause?                                           # TryStmt
    | TRY block finallyClause                                                         # TryFinallyStmt
    | label statement                                                                 # LabeledStmt
    | block                                                                           # BlockStmt
    ;

exprBlock
    : block
    | expression
    ;

elseifClause
    : ELSEIF expression exprBlock
    ;

elseClause
    : ELSE exprBlock
    ;

matchCase
    : patternList (IF expression)? FAT_ARROW matchCaseBody (COMMA | SEMICOLON)?   # GuardedMatchCase
    | patternList FAT_ARROW matchCaseBody (COMMA | SEMICOLON)?                    # NormalCase
    | DEFAULT FAT_ARROW matchCaseBody (COMMA | SEMICOLON)?                        # DefaultCase
    ;

patternList
    : pattern (COMMA pattern)*
    ;

matchCaseBody
    : block
    | expression
    ;

pattern
    : literal                                         # LiteralPattern
    | expression (RANGE_EXCL | RANGE_INCL) expression # RangePattern
    | IS typeType                                     # IsPattern
    | IDENTIFIER                                      # IdentifierPattern
    | typeType                                        # TypePattern
    | IDENTIFIER (COMMA IDENTIFIER)+                  # DestructurePattern
    ;

catchClause
    : CATCH LPAREN IDENTIFIER COLON typeType RPAREN block
    ;

finallyClause
    : FINALLY block
    ;

label
    : IDENTIFIER COLON
    ;

labelRef
    : IDENTIFIER
    ;

block
    : LBRACE statement* RBRACE
    ;

// ==========================
// Expressions (v1.1 section 7, operator precedence from 5.6)
// ==========================

expression
    : primaryExpression                                   # PrimaryExpr
    | expression DOT IDENTIFIER                           # MemberAccess
    | expression COLON_COLON IDENTIFIER                   # StaticMemberAccess
    | expression QUESTION_DOT IDENTIFIER                  # SafeMemberAccess
    | expression LBRACK expression RBRACK                 # IndexAccess
    | expression LPAREN argumentList? RPAREN              # FunctionCall
    | NONNULL expression                                  # NonNullAssert
    | (NOT | PLUS | MINUS) expression                     # UnaryExpr
    | expression (STAR | SLASH | PERCENT) expression      # MulDivMod
    | expression (PLUS | MINUS) expression                # AddSub
    | expression (RANGE_EXCL | RANGE_INCL) expression     # Range
    | expression (IS typeType)                            # TypeCheck
    | expression (AS typeType)                            # AsCast
    | expression IN expression                            # InExpression
    | expression (LT | GT | LE | GE) expression           # Comparison
    | expression (EQ | NEQ) expression                    # Equality
    | expression AND expression                           # LogicalAnd
    | expression OR expression                            # LogicalOr
    | expression ELVIS expression                         # Elvis
    | IF expression exprBlock elseifClause* elseClause?       # IfExpr
    | MATCH expression LBRACE matchCase+ RBRACE           # MatchExpr
    | <assoc=right> expression assignmentOperator expression  # Assignment
    ;

primaryExpression
    : literal                                    # LiteralExpr
    | IDENTIFIER                                 # IdentifierExpr
    | SELF LPAREN argumentList? RPAREN           # SelfConstructorCall
    | SELF                                       # SelfExpr
    | SUPER LPAREN argumentList? RPAREN          # SuperConstructorCall
    | SUPER                                      # SuperExpr
    | AWAIT expression                           # AwaitExpr
    | LPAREN expression RPAREN                   # ParenExpr
    | arrayLiteral                               # ArrayLit
    | lambdaExpression                           # LambdaExpr
    | objectCreation                             # ObjectCreationExpr
    | threadExpression                           # ThreadExpr
    ;

arrayLiteral
    : LBRACK elementList? RBRACK
    ;

elementList
    : expression (COMMA expression)*
    ;

lambdaExpression
    : lambdaParameters FAT_ARROW lambdaBody
    ;

lambdaType
    : typedLambdaParameters FAT_ARROW typeType
    ;

typedLambdaParameters
    : LPAREN RPAREN                           # EmptyTypedLambdaParams
    | LPAREN typeTypeList RPAREN             # TypedTypedLambdaParams
    ;

lambdaParameters
    : LPAREN RPAREN                           # EmptyLambdaParams
    | IDENTIFIER                              # SingleLambdaParam
    | LPAREN IDENTIFIER (COMMA IDENTIFIER)* RPAREN  # MultiLambdaParams
    | LPAREN parameterList RPAREN             # TypedLambdaParams
    ;

lambdaBody
    : expression
    | block
    ;

objectCreation
    : recordDeclaration                         # RecordConstruction
    ;

threadExpression
    : THREAD IDENTIFIER (LPAREN argumentList? RPAREN)? block
    ;

argumentList
    : namedArgument (COMMA namedArgument)*
    ;

namedArgument
    : (IDENTIFIER EQ)? expression            # NormalArgument
    | STAR expression                           # VarargExpansion
    ;

assignmentOperator
    : ASSIGN
    | PLUS_ASSIGN
    | MINUS_ASSIGN
    | STAR_ASSIGN
    | SLASH_ASSIGN
    | PERCENT_ASSIGN
    ;

literal
    : INTEGER_LITERAL
    | LONG_LITERAL
    | FLOAT_LITERAL
    | DOUBLE_LITERAL
    | STRING_LITERAL
    | TRUE
    | FALSE
    | NULL
    ;

// ==========================
// Types (v1.1 section 3)
// ==========================

typeType
    : simpleType typeSuffix*
    ;

typeSuffix
    : QUESTION                          # NullableType
    | LBRACK RBRACK                     # ArrayType
    | LBRACK INTEGER_LITERAL RBRACK     # SizedArrayType
    ;

simpleType
    : primitiveType
    | qualifiedName genericArguments?
    | lambdaType
    ;

primitiveType
    : VOID
    | BOOL
    | INT
    | LONG
    | FLOAT
    | DOUBLE
    | STRING
    | OBJECT
    | NONE
    ;

genericParameters
    : LT genericParameter (COMMA genericParameter)* GT
    ;

genericParameter
    : (IN | OUT)? IDENTIFIER (COLON typeTypeList)? (DEFAULT typeType)?
    ;

genericArguments
    : LT typeTypeList GT
    ;

typeTypeList
    : typeType (COMMA typeType)*
    ;

// ==========================
// Modifiers & Visibility (v1.1 section 8.1.2)
// ==========================

visibility
    : PUB
    | PROTECTED
    | LOCAL
    ;

inheritance
    : COLON inheritanceItem (COMMA inheritanceItem)*
    ;

inheritanceItem
    : typeType (LPAREN argumentList? RPAREN)?
    ;

// ==========================
// End of Grammar
// ==========================
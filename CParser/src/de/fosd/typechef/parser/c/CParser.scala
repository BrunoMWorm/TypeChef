package de.fosd.typechef.parser.c
import org.anarres.cpp.Token

import de.fosd.typechef.parser._
import de.fosd.typechef.featureexpr.FeatureExpr
/*%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
 * based on ANTLR grammar from John D. Mitchell (john@non.net), Jul 12, 1997
 * and Monty Zukowski (jamz@cdsnet.net) April 28, 1998
 */

class CParser extends MultiFeatureParser {
    type Elem = TokenWrapper
    type Context = CTypeContext

    def parse(code: String, mainProduction: (TokenReader[TokenWrapper, CTypeContext], FeatureExpr) => MultiParseResult[AST, TokenWrapper, CTypeContext]): MultiParseResult[AST, TokenWrapper, CTypeContext] =
        mainProduction(CLexer.lex(code), FeatureExpr.base)

    def parseAny(code: String, mainProduction: (TokenReader[TokenWrapper, CTypeContext], FeatureExpr) => MultiParseResult[Any, TokenWrapper, CTypeContext]): MultiParseResult[Any, TokenWrapper, CTypeContext] =
        mainProduction(CLexer.lex(code), FeatureExpr.base)

    //parser
    val keywords = Set("__real__", "__imag__", "__alignof", "__asm", "__asm__", "__attribute__",
        "__complex__", "__const", "__const__", "__inline", "__inline__", 
        "__signed", "__signed__", "__typeof", "__typeof__", "__volatile", "__volatile__", "asm",
        "volatile", "typeof", "auto", "register", "typedef", "extern", "static", "inline",
        "const", "volatile", "restrict", "char", "short", "int", "long", "float", "double",
        "signed", "unsigned", "_Bool", "type", "struct", "union", "enum", "if", "while", "do",
        "for", "goto", "continue", "break", "return", "case", "default", "else", "switch",
        "sizeof", "__extension__")

    def translationUnit = externalList

    def externalList =
        repOpt(externalDef, AltExternalDef.join)

    def externalDef: MultiParser[ExternalDef] =
        (declaration | functionDef | typelessDeclaration | asm_expr | SEMI ^^ { x => EmptyExternalDef() }) ^^! (AltExternalDef.join, x => x)

    def asm_expr: MultiParser[AsmExpr] =
        asm ~ opt(volatile) ~ LCURLY ~ expr ~ RCURLY ~ rep1(SEMI) ^^
            { case _ ~ v ~ _ ~ e ~ _ ~ _ => AsmExpr(v.isDefined, e) }

    def declaration: MultiParser[Declaration] =
        (declSpecifiers ~ opt(initDeclList) ~ rep1(SEMI) ^^ { case d ~ i ~ _ => ADeclaration(d, i) } changeContext ({ (result: ADeclaration, context: Context) =>
            {
                var c = context
                if (result.declSpecs.contains(TypedefSpecifier()))
                    if (result.init.isDefined)
                        for (decl: InitDeclarator <- result.init.get)
                            c = c.addType(decl.declarator.getName)
                c
            }
        })) ^^! (AltDeclaration.join, x => x)

    //gnu
    def typelessDeclaration: MultiParser[TypelessDeclaration] =
        initDeclList <~ SEMI ^^ { x => TypelessDeclaration(x) }

    def declSpecifiers: MultiParser[List[Specifier]] =
        rep1(storageClassSpecifier | typeQualifier | typeSpecifier | attributeDecl) | fail("declSpecifier expected")

    def storageClassSpecifier: MultiParser[Specifier] =
        specifier("auto") | specifier("register") | textToken("typedef") ^^ { x => TypedefSpecifier() } | functionStorageClassSpecifier

    def functionStorageClassSpecifier: MultiParser[Specifier] =
        specifier("extern") | specifier("static") | inline

    def typeQualifier: MultiParser[Specifier] =
        const | volatile | specifier("restrict")

    def specifier(name: String) = textToken(name) ^^ { t => OtherSpecifier(t.getText) }

    def typeSpecifier: MultiParser[TypeSpecifier] = ((textToken("void")
        | textToken("char")
        | textToken("short")
        | textToken("int")
        | textToken("long")
        | textToken("float")
        | textToken("double")
        | signed
        | textToken("unsigned")
        | textToken("_Bool")
        | textToken("_Complex")
        | textToken("__complex__")) ^^ { (t: Elem) => PrimitiveTypeSpecifier(t.getText) }
        | structOrUnionSpecifier
        | enumSpecifier
        | typedefName ^^ { TypeDefTypeSpecifier(_) }
        | typeof ~ LPAREN ~> (typeName ^^ { TypeOfSpecifierT(_) } | expr ^^ { TypeOfSpecifierU(_) }) <~ RPAREN)

    def typedefName = tokenWithContext("type", (token, context) => isIdentifier(token) && context.knowsType(token.getText)) ^^ { t => Id(t.getText) }

    def structOrUnionSpecifier: MultiParser[StructOrUnionSpecifier] =
        structOrUnion ~ structOrUnionSpecifierBody ^^ { case ~(k, (id, list)) => StructOrUnionSpecifier(k, id, list) }

    private def structOrUnionSpecifierBody: MultiParser[(Option[Id], List[StructDeclaration])] =
        ID ~ LCURLY ~! (opt(SEMI) ~ optList(structDeclarationList) ~ RCURLY) ^^ { case id ~ _ ~(_ ~ list ~ _) => (Some(id), list) } |
            LCURLY ~ opt(SEMI) ~ optList(structDeclarationList) ~ RCURLY ^^ { case _ ~ _ ~ list ~ _ => (None, list) } |
            ID ^^ { case id => (Some(id), List()) }

    def structOrUnion: MultiParser[String] =
        (textToken("struct") | textToken("union")) ^^ { case t: TokenWrapper => t.getText }

    def structDeclarationList =
        rep1(structDeclaration)

    def structDeclaration: MultiParser[StructDeclaration] =
        specifierQualifierList ~ structDeclaratorList <~ (opt(COMMA) ~ rep1(SEMI)) ^^ { case q ~ l => StructDeclaration(q, l) }

    def specifierQualifierList: MultiParser[List[Specifier]] =
        rep1(typeSpecifier | typeQualifier | attributeDecl)

    def structDeclaratorList: MultiParser[List[StructDeclarator]] =
        repSep(structDeclarator, COMMA)

    def structDeclarator: MultiParser[StructDeclarator] =
        (COLON ~> constExpr ~ rep(attributeDecl) ^^ { case e ~ attr => StructDeclarator(None, Some(e), attr) }
            | declarator ~ opt(COLON ~> constExpr) ~ rep(attributeDecl) ^^ { case d ~ e ~ attr => StructDeclarator(Some(d), e, attr) })

    def enumSpecifier: MultiParser[EnumSpecifier] =
        textToken("enum") ~>
            (ID ~ LCURLY ~! (enumList ~ RCURLY) ^^ { case id ~ _ ~(l ~ _) => EnumSpecifier(Some(id), l) }
                | LCURLY ~ enumList ~ RCURLY ^^ { case _ ~ l ~ _ => EnumSpecifier(None, l) }
                | ID ^^ { case i => EnumSpecifier(Some(i), List()) })

    def enumList: MultiParser[List[Enumerator]] =
        rep1Sep(enumerator, COMMA) <~ opt(COMMA)

    def enumerator: MultiParser[Enumerator] =
        ID ~ opt(ASSIGN ~> constExpr) ^^ { case id ~ expr => Enumerator(id, expr) }

    def initDeclList: MultiParser[List[InitDeclarator]] =
        rep1Sep(initDecl, COMMA) <~ opt(COMMA)

    def initDecl: MultiParser[InitDeclarator] =
        declarator ~ rep(attributeDecl) ~ opt(ASSIGN ~> initializer | COLON ~> expr) ^^
            { case d ~ attr ~ Some(i: Initializer) => InitDeclaratorI(d, attr, Some(i)); case d ~ attr ~ Some(e: Expr) => InitDeclaratorE(d, attr, e); case d ~ attr ~ None => InitDeclaratorI(d, attr, None); }

    def pointerGroup: MultiParser[List[Pointer]] =
        rep1(STAR ~> opt(typeQualifierList) ^^ { case Some(l) => Pointer(l); case None => Pointer(List()) })

    def typeQualifierList: MultiParser[List[Specifier]] =
        rep(typeQualifier | attributeDecl)

    def idList: MultiParser[List[Id]] =
        rep1Sep(ID, COMMA)

    def initializer: MultiParser[Initializer] =
        opt(initializerElementLabel) ~ (assignExpr | lcurlyInitializer) ^^ { case iel ~ expr => Initializer(iel, expr) }

    def declarator: MultiParser[Declarator] =
        (optList(pointerGroup) ~ (ID | LPAREN ~> declarator <~ RPAREN) ~
            rep(
                LPAREN ~> (parameterTypeList ^^ { DeclParameterTypeList(_) }
                    | optList(idList) ^^ { DeclIdentifierList(_) }) <~ (opt(COMMA) ~ RPAREN)
                | LBRACKET ~> opt(constExpr) <~ RBRACKET ^^ { DeclArrayAccess(_) }
                )) ^^ {
            case pointers ~(id: Id) ~ ext => DeclaratorId(pointers, id, ext);
            case pointers ~(decl: Declarator) ~ ext => DeclaratorDecl(pointers, decl, ext)
        }

    def parameterTypeList: MultiParser[List[ParameterDeclaration]] =
        rep1Sep(parameterDeclaration, COMMA | SEMI) ~ opt((COMMA | SEMI) ~> VARARGS) ^^
            { case l ~ Some(v) => l ++ List(VarArgs()); case l ~ None => l }

    def parameterDeclaration: MultiParser[ParameterDeclaration] =
        declSpecifiers ~ opt(declarator | nonemptyAbstractDeclarator) ^^
            {
                case s ~ Some(d: Declarator) => ParameterDeclarationD(s, d)
                case s ~ Some(d: AbstractDeclarator) => ParameterDeclarationAD(s, d)
                case s ~ None => ParameterDeclaration(s)
            }

    def functionDef: MultiParser[FunctionDef] =
        optList(functionDeclSpecifiers) ~
            declarator ~
            rep(declaration) ~ opt2List(VARARGS ^^ { x => VarArgs() }) ~ rep(SEMI) ~
            lookahead(LCURLY) ~! //prevents backtracking inside function bodies
            compoundStatement ^^
            { case sp ~ declarator ~ param ~ vparam ~ _ ~ _ ~ stmt => FunctionDef(sp, declarator, param ++ vparam, stmt) }

    def functionDeclSpecifiers: MultiParser[List[Specifier]] =
        rep1(functionStorageClassSpecifier | typeQualifier | typeSpecifier | attributeDecl)

    private def compoundDeclarations =
        declaration | nestedFunctionDef | localLabelDeclaration

    private def compoundDeclarationList: MultiParser[List[Opt[Declaration]]] =
        (compoundDeclarations) ~ repOpt(compoundDeclarations, AltDeclaration.join) ^^ { case d ~ l => List(Opt(FeatureExpr.base, d)) ++ l }

    def compoundStatement: MultiParser[CompoundStatement] =
        LCURLY ~> optList(compoundDeclarationList) ~ statementList <~ RCURLY ^^ { case decl ~ stmt => CompoundStatement(decl, stmt) }

    def statementList: MultiParser[List[Opt[Statement]]] =
        repOpt(statement, AltStatement.join)

    def statement: MultiParser[Statement] = (SEMI ^^ { _ => EmptyStatement() } // Empty statements
        | compoundStatement // Group of statements
        | expr <~ SEMI ^^ { ExprStatement(_) } // Expressions
        //// Iteration statements:
        | textToken("while") ~ LPAREN ~ expr ~ RPAREN ~ statement ^^ { case _ ~ _ ~ e ~ _ ~ s => WhileStatement(e, s) }
        | textToken("do") ~ statement ~ textToken("while") ~ LPAREN ~ expr ~ RPAREN ~ SEMI ^^ { case _ ~ s ~ _ ~ _ ~ e ~ _ ~ _ => DoStatement(e, s) }
        | textToken("for") ~ LPAREN ~ opt(expr) ~ SEMI ~ opt(expr) ~ SEMI ~ opt(expr) ~ RPAREN ~ statement ^^ { case _ ~ _ ~ e1 ~ _ ~ e2 ~ _ ~ e3 ~ _ ~ s => ForStatement(e1, e2, e3, s) } //                                    {
        //// Jump statements:
        | textToken("goto") ~> ID <~ SEMI ^^ { GotoStatement(_) }
        | textToken("continue") ~ SEMI ^^ { _ => ContinueStatement() }
        | textToken("break") ~ SEMI ^^ { _ => BreakStatement() }
        | textToken("return") ~> opt(expr) <~ SEMI ^^ { ReturnStatement(_) }
        //// Labeled statements:
        | ID <~ COLON ^^ { LabelStatement(_) }
        // GNU allows range expressions in case statements
        | textToken("case") ~ (rangeExpr | constExpr) ~ COLON ~ opt(statement) ^^ { case _ ~ e ~ _ ~ s => CaseStatement(e, s) }
        | textToken("default") ~ COLON ~> opt(statement) ^^ { DefaultStatement(_) }
        //// Selection statements:
        | textToken("if") ~ LPAREN ~ expr ~ RPAREN ~ statement ~ opt(textToken("else") ~> statement) ^^ { case _ ~ _ ~ ex ~ _ ~ ts ~ es => IfStatement(ex, ts, es) }
        | textToken("switch") ~ LPAREN ~ expr ~ RPAREN ~ statement ^^ { case _ ~ _ ~ e ~ _ ~ s => SwitchStatement(e, s) }) ^^! (AltStatement.join, s => s) | fail("statement expected")

    def expr: MultiParser[Expr] = assignExpr ~ rep(COMMA ~> assignExpr) ^^
        { case e ~ l => if (l.isEmpty) e else ExprList(List(e) ++ l) }

    def assignExpr: MultiParser[Expr] =
        conditionalExpr ~ opt(assignOperator ~ assignExpr) ^^
            { case e ~ Some(o ~ e2) => AssignExpr(e, o.getText, e2); case e ~ None => e }

    def assignOperator = (ASSIGN
        | DIV_ASSIGN
        | PLUS_ASSIGN
        | MINUS_ASSIGN
        | STAR_ASSIGN
        | MOD_ASSIGN
        | RSHIFT_ASSIGN
        | LSHIFT_ASSIGN
        | BAND_ASSIGN
        | BOR_ASSIGN
        | BXOR_ASSIGN)

    def conditionalExpr: MultiParser[Expr] = logicalOrExpr ~ opt(QUESTION ~ opt(expr) ~ COLON ~ conditionalExpr) ^^
        { case e ~ Some(q ~ e2 ~ c ~ e3) => ConditionalExpr(e, e2, e3); case e ~ None => e }
    def constExpr = conditionalExpr
    def logicalOrExpr: MultiParser[Expr] = nAryExpr(logicalAndExpr, LOR)
    def logicalAndExpr: MultiParser[Expr] = nAryExpr(inclusiveOrExpr, LAND)
    def inclusiveOrExpr: MultiParser[Expr] = nAryExpr(exclusiveOrExpr, BOR)
    def exclusiveOrExpr: MultiParser[Expr] = nAryExpr(bitAndExpr, BXOR)
    def bitAndExpr: MultiParser[Expr] = nAryExpr(equalityExpr, BAND)
    def equalityExpr: MultiParser[Expr] = nAryExpr(relationalExpr, EQUAL | NOT_EQUAL)
    def relationalExpr: MultiParser[Expr] = nAryExpr(shiftExpr, LT | LTE | GT | GTE)
    def shiftExpr: MultiParser[Expr] = nAryExpr(additiveExpr, LSHIFT | RSHIFT)
    def additiveExpr: MultiParser[Expr] = nAryExpr(multExpr, PLUS | MINUS)
    def multExpr: MultiParser[Expr] = nAryExpr(castExpr, STAR | DIV | MOD)

    def nAryExpr(innerExpr: MultiParser[Expr], operations: MultiParser[TokenWrapper]) =
        innerExpr ~ rep(operations ~ innerExpr ^^ { case t ~ e => (t.getText, e) }) ^^ { case e ~ l => if (l.isEmpty) e else NAryExpr(e, l) }

    def castExpr: MultiParser[Expr] =
        LPAREN ~ typeName ~ RPAREN ~! (castExpr | lcurlyInitializer) ^^ { case b1 ~ t ~ b2 ~ e => CastExpr(t, e) } | unaryExpr

    def nonemptyAbstractDeclarator: MultiParser[AbstractDeclarator] =
        (pointerGroup ~
            rep((LPAREN ~> (nonemptyAbstractDeclarator | optList(parameterTypeList) ^^ { DeclParameterTypeList(_) }) <~ RPAREN)
                | (LBRACKET ~> opt(expr) <~ (opt(COMMA) ~ RBRACKET) ^^ { DeclArrayAccess(_) })
                ) ^^ { case pointers ~ directDecls => AbstractDeclarator(pointers, directDecls) }

            | rep1((LPAREN ~> (nonemptyAbstractDeclarator | optList(parameterTypeList) ^^ { DeclParameterTypeList(_) }) <~ RPAREN)
                | (LBRACKET ~> opt(expr) <~ (opt(COMMA) ~ RBRACKET) ^^ { DeclArrayAccess(_) })
                ) ^^ { AbstractDeclarator(List(), _) })

    def unaryExpr: MultiParser[Expr] = (postfixExpr
        | { INC ~ unaryExpr | DEC ~ unaryExpr } ^^ { case p ~ e => UnaryExpr(p.getText, e) }
        | unaryOperator ~ castExpr ^^ { case u ~ c => UCastExpr(u.getText, c) }
        | textToken("sizeof") ~> {
            LPAREN ~> typeName <~ RPAREN ^^ { SizeOfExprT(_) } |
                unaryExpr ^^ { SizeOfExprU(_) }
        }
        | textToken("__alignof") ~> {
            LPAREN ~> typeName <~ RPAREN ^^ { AlignOfExprT(_) } |
                unaryExpr ^^ { AlignOfExprU(_) }
        }
        | gnuAsmExpr)

    def unaryOperator = (BAND | STAR | PLUS | MINUS | BNOT | LNOT
        | LAND //for label dereference (&&label)
        | textToken("__real__")
        | textToken("__imag__"))

    def postfixExpr = primaryExpr ~ postfixSuffix ^^ { case p ~ s => if (s.isEmpty) p else PostfixExpr(p, s) }

    def postfixSuffix: MultiParser[List[PostfixSuffix]] = rep[PostfixSuffix](
        { PTR ~ ID | DOT ~ ID } ^^ { case ~(e, id: Id) => PointerPostfixSuffix(e.getText, id) }
        | functionCall
        | LBRACKET ~> expr <~ RBRACKET ^^ { ArrayAccess(_) }
        | { INC | DEC } ^^ { t => SimplePostfixSuffix(t.getText) }
        )
    //
    def functionCall: MultiParser[FunctionCall] =
        LPAREN ~> opt(argExprList) <~ RPAREN ^^ { case Some(l) => FunctionCall(l); case None => FunctionCall(ExprList(List())) }

    def primaryExpr: MultiParser[Expr] =
        (textToken("__builtin_offsetof") ~ LPAREN ~ typeName ~ COMMA ~ offsetofMemberDesignator ~ RPAREN ^^ { case _ ~ _ ~ tn ~ _ ~ d ~ _ => BuildinOffsetof(tn, d) }
            | ID
            | numConst
            | stringConst
            | LPAREN ~ lookahead(LCURLY) ~! compoundStatement ~ RPAREN ^^ { case _ ~ _ ~ cs ~ _ => CompoundStatementExpr(cs) }
            | LPAREN ~> expr <~ RPAREN
            | fail("primary expression expected"))

    def typeName: MultiParser[TypeName] =
        specifierQualifierList ~ opt(nonemptyAbstractDeclarator) ^^ { case sl ~ d => TypeName(sl, d) }

    def ID: MultiParser[Id] = token("id", isIdentifier(_)) ^^ { t => Id(t.getText) }

    def isIdentifier(token: TokenWrapper) = token.isIdentifier &&
        !keywords.contains(token.getText)

    def stringConst: MultiParser[StringLit] = token("string literal", _.getType == Token.STRING) ^^ { t => StringLit(t.getText) }; def numConst: MultiParser[Constant] = token("number", _.isInteger) ^^ { t => Constant(t.getText) } |
        token("number", _.getType == Token.CHARACTER) ^^ { t => Constant(t.getText) }

    def argExprList: MultiParser[ExprList] =
        rep1Sep(assignExpr, COMMA) ^^ { ExprList(_) }

    //
    def ASSIGN = textToken('=')
    def COLON = textToken(':')
    def COMMA = textToken(',')
    def QUESTION = textToken('?')
    def SEMI = textToken(';')
    def PTR = textToken("->")
    def VARARGS = textToken("...")
    def DOT = textToken(".")
    def LPAREN = textToken('(')
    def RPAREN = textToken(')')
    def LBRACKET = textToken('[')
    def RBRACKET = textToken(']')
    def LCURLY = textToken('{')
    def RCURLY = textToken('}')
    //
    def EQUAL = textToken("==")
    def NOT_EQUAL = textToken("!=")
    def LTE = textToken("<=")
    def LT = textToken("<")
    def GTE = textToken(">=")
    def GT = textToken(">")
    //
    def DIV = textToken('/')
    def DIV_ASSIGN = textToken("/=")
    def PLUS = textToken('+')
    def PLUS_ASSIGN = textToken("+=")
    def INC = textToken("++")
    def MINUS = textToken('-')
    def MINUS_ASSIGN = textToken("-=")
    def DEC = textToken("--")
    def STAR = textToken('*')
    def STAR_ASSIGN = textToken("*=")
    def MOD = textToken('%')
    def MOD_ASSIGN = textToken("%=")
    def RSHIFT = textToken(">>")
    def RSHIFT_ASSIGN = textToken(">>=")
    def LSHIFT = textToken("<<")
    def LSHIFT_ASSIGN = textToken("<<=")
    //
    def LAND = textToken("&&")
    def LNOT = textToken('!')
    def LOR = textToken("||")
    //
    def BAND = textToken('&')
    def BAND_ASSIGN = textToken("&=")
    def BNOT = textToken('~')
    def BOR = textToken('|')
    def BOR_ASSIGN = textToken("|=")
    def BXOR = textToken('^')
    def BXOR_ASSIGN = textToken("^=")

    //***  gnuc extensions ****************************************************

    def attributeDecl: MultiParser[Specifier] =
        ((textToken("__attribute__") | textToken("__extension__") /*own*/ ) ~
            LPAREN ~ LPAREN ~ attributeList ~ RPAREN ~ RPAREN ^^ { case _ ~ _ ~ _ ~ al ~ _ ~ _ => AttributeSpecifier(al) } |
            asm ~ LPAREN ~> stringConst <~ RPAREN ^^ { AsmAttributeSpecifier(_) })

    def attributeList: MultiParser[List[List[Attribute]]] =
        attribute ~ rep(COMMA ~> attribute) ~ opt(COMMA) ^^ { case (attr: List[Attribute]) ~(attrList: List[List[Attribute]]) ~ _ => List(attr) ++ attrList }

    def attribute: MultiParser[List[Attribute]] =
        rep(anyTokenExcept(List("(", ")", ",")) ^^ { t => AtomicAttribute(t.getText) }
            | LPAREN ~> attributeList <~ RPAREN ^^ { t => CompoundAttribute(t) }
            )

    def offsetofMemberDesignator: MultiParser[List[Id]] =
        rep1Sep(ID, DOT)

    def gnuAsmExpr: MultiParser[GnuAsmExpr] =
        asm ~ opt(volatile) ~
            LPAREN ~ rep1(stringConst) ~
            opt(
                COLON ~> opt(strOptExprPair ~ opt(COMMA ~> strOptExprPair))
                ~ opt(
                    COLON ~> opt(strOptExprPair ~ rep(COMMA ~> strOptExprPair)) ~
                    opt(COLON ~> stringConst ~ rep(COMMA ~> stringConst))
                    )
                ) ~
            RPAREN ^^ { case _ ~ v ~ _ ~ e ~ stuff ~ _ => GnuAsmExpr(v.isDefined, e, stuff) }

    //GCC requires the PARENs
    def strOptExprPair =
        opt(LBRACKET ~> ID <~ RBRACKET) ~ stringConst ~ opt(LPAREN ~> expr <~ RPAREN)

    // GCC allows empty initializer lists
    def lcurlyInitializer: MultiParser[Expr] =
        LCURLY ~ optList(initializerList <~ opt(COMMA)) ~ RCURLY ^^ { case _ ~ inits ~ _ => LcurlyInitializer(inits) }

    def initializerList: MultiParser[List[Initializer]] =
        rep1Sep(initializer, COMMA)

    def rangeExpr: MultiParser[Expr] = //used in initializers only  
        constExpr ~ VARARGS ~! constExpr ^^ { case a ~ _ ~ b => RangeExpr(a, b) }

    def nestedFunctionDef: MultiParser[NestedFunctionDef] =
        opt(textToken("auto")) ~ //only for nested functions
            optList(functionDeclSpecifiers) ~
            declarator ~
            rep(declaration) ~
            compoundStatement ^^
            { case auto ~ sp ~ declarator ~ param ~ stmt => NestedFunctionDef(auto.isDefined, sp, declarator, param, stmt) }

    //GNU note:  any __label__ declarations must come before regular declarations.            
    def localLabelDeclaration: MultiParser[LocalLabelDeclaration] =
        textToken("__label__") ~> rep1Sep(ID, COMMA) <~ (opt(COMMA) ~ rep1(SEMI)) ^^ { LocalLabelDeclaration(_) }

    // GCC allows more specific initializers
    def initializerElementLabel: MultiParser[InitializerElementLabel] =
        (LBRACKET ~ (rangeExpr | constExpr) ~ RBRACKET ~ opt(ASSIGN) ^^ { case _ ~ e ~ _ ~ a => InitializerElementLabelExpr(e, a.isDefined) }
            | ID <~ COLON ^^ { InitializerElementLabelColon(_) }
            | DOT ~> ID <~ ASSIGN ^^ { InitializerElementLabelDotAssign(_) })

    def typeof = textToken("typeof") | textToken("__typeof") | textToken("__typeof__")

    def volatile = specifier("volatile") | specifier("__volatile") | specifier("__volatile__")
    
    def asm = textToken("asm") | textToken("__asm") | textToken("__asm__")

    def const = specifier("const") | specifier("__const")|specifier("__const__")
    
    def signed = textToken("signed") | textToken("__signed") | textToken("__signed__")
 
    def inline = specifier("inline") | specifier("__inline") | specifier("__inline__")
    
    // *** helper functions
    def textToken(t: String): MultiParser[Elem] =
        token(t, _.getText == t)

    def textToken(t: Char) =
        token(t.toString, _.getText == t.toString)

    def anyTokenExcept(exceptions: List[String]): MultiParser[Elem] =
        token("any except " + exceptions, (t: Elem) => !exceptions.contains(t.getText))

}
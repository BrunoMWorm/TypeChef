package de.fosd.typechef.typesystem


import de.fosd.typechef.parser.c._
import de.fosd.typechef.conditional._
import de.fosd.typechef.featureexpr.FeatureExpr
import org.kiama.attribution.Attribution._
import org.kiama._

/**
 * typing C expressions
 */
trait CExprTyping extends CTypes with CTypeEnv with CDeclTyping {

    def ctype(expr: Expr): TConditional[CType] = expr -> exprType
    def ctype(expr: Expr, context: AST): TConditional[CType] =
        getExprType(outerVarEnv(context), outerStructEnv(context), expr)
    val exprType: Expr ==> TConditional[CType] = attr {
        case expr => getExprType(expr -> varEnv, expr -> structEnv, expr)
    }

    def getStmtType(stmt: Statement): TConditional[CType]
    //implemented by CStmtTyping

    private def structEnvLookup(strEnv: StructEnv, structName: String, isUnion: Boolean, fieldName: String): TConditional[CType] = {
        assert(strEnv.someDefinition(structName, isUnion), "struct/union " + structName + " unknown") //should not occur by construction. system won't build a struct type if its name is not in the struct table

        val struct: ConditionalTypeMap = strEnv.get(structName, isUnion)
        struct.getOrElse(fieldName, CUnknown("field " + fieldName + " unknown in " + structName))
    }

    //    private def anonymousStructLookup(fields: List[(String, CType)], fieldName:String):CType =
    //        if (fie)

    private[typesystem] def getExprType(varCtx: VarTypingContext, strEnv: StructEnv, expr: Expr): TConditional[CType] = TOne(getExprTypeOne(varCtx, strEnv, expr))

    //TODO proper variability
    private def getExprTypeOne(varCtx: VarTypingContext, strEnv: StructEnv, expr: Expr): CType = {
        val et = getExprTypeOne(varCtx, strEnv, _: Expr)
        //TODO assert types in varCtx and funCtx are welltyped and non-void
        expr match {
            /**
             * The standard provides for methods of
             * specifying constants in unsigned, long and oating point types; we omit
             * these for brevity's sake
             */
            //TODO constant 0 is special, can be any pointer or function
            case Constant(_) => CSigned(CInt())
            //variable or function ref TODO check
            case Id(name) => __makeOne(varCtx(name)).toObj
            //&a: create pointer
            case PointerCreationExpr(expr) =>
                et(expr) match {
                    case CObj(t) => CPointer(t)
                    case e => CUnknown("& on " + e)
                }
            //*a: pointer dereferencing
            case PointerDerefExpr(expr) =>
                et(expr).toValue match {
                    case CPointer(t) if (t != CVoid) => t.toObj
                    case f: CFunction => f // for some reason deref of a function still yields a valid function in gcc
                    case e => CUnknown("* on " + e)
                }
            //e.n notation
            case PostfixExpr(expr, PointerPostfixSuffix(".", Id(id))) =>
                def lookup(fields: ConditionalTypeMap) = __makeOne(fields.getOrElse(id, CUnknown("field not found: (" + expr + ")." + id + "; has " + fields)))
                et(expr) match {
                    case CObj(CAnonymousStruct(fields, _)) => lookup(fields).toObj
                    case CAnonymousStruct(fields, _) => lookup(fields)
                    case CObj(CStruct(s, isUnion)) => __makeOne(structEnvLookup(strEnv, s, isUnion, id)).toObj
                    case CStruct(s, isUnion) => __makeOne(structEnvLookup(strEnv, s, isUnion, id)) match {
                        case e if (arrayType(e)) => CUnknown("(" + e + ")." + id + " has array type")
                        case e => e
                    }
                    case e => CUnknown("(" + e + ")." + id)
                }
            //e->n
            case PostfixExpr(expr, PointerPostfixSuffix("->", Id(id))) =>
                et(PostfixExpr(PointerDerefExpr(expr), PointerPostfixSuffix(".", Id(id))))
            //(a)b
            case CastExpr(targetTypeName, expr) =>
                val targetType = __makeOne(ctype(targetTypeName))
                val sourceType = et(expr).toValue
                if (targetType == CVoid() || (isScalar(sourceType) && isScalar(targetType))) targetType
                else if (isCompound(sourceType) && (isStruct(targetType) || isArray(targetType))) targetType //workaround for array/struct initializers
                else if (sourceType == CIgnore()) targetType
                else
                    CUnknown("incorrect cast from " + sourceType + " to " + targetType)
            //a()
            case PostfixExpr(expr, FunctionCall(ExprList(parameterExprs))) =>
                //TODO ignoring variability for now
                et(expr).toValue map {
                    case CPointer(CFunction(p, r)) => typeFunctionCall(expr, p, r, parameterExprs.map({case Opt(_, e) => et(e)}))
                    case CFunction(parameterTypes, retType) => typeFunctionCall(expr, parameterTypes, retType, parameterExprs.map({case Opt(_, e) => et(e)}))
                    case x: CUnknown => x
                    case e => CUnknown(expr + " is not a function, but " + e)
                }
            //a=b, a+=b, ...
            case AssignExpr(texpr, op, sexpr) =>
                val stype = et(sexpr)
                val ttype = et(texpr)
                val opType = operationType(op, ttype, stype)
                ttype match {
                    case CObj(t) if (!arrayType(t) && coerce(t, opType)) => t
                    case e => CUnknown("incorrect assignment with " + e + " " + op + " " + stype)
                }
            //a++, a--
            case PostfixExpr(expr, SimplePostfixSuffix(_)) => et(expr) match {
                case CObj(t) if (isScalar(t)) => t
                //TODO check?: not on function references
                case e => CUnknown("incorrect post increment/decrement on type " + e)
            }
            //a+b
            case NAryExpr(expr, opList) =>
                //TODO ignoring variability for now
                var result = et(expr)
                for (Opt(_, NArySubExpr(op, thatExpr)) <- opList) {
                    val thatType = et(thatExpr)
                    result = operationType(op, result, thatType)
                }
                result
            //a[e]
            case PostfixExpr(expr, ArrayAccess(idx)) =>
                //syntactic sugar for *(a+i)
                et(PointerDerefExpr(createSum(expr, idx)))
            //"a"
            case StringLit(_) => CPointer(CSignUnspecified(CChar())) //unspecified sign according to Paolo
            //++a, --a
            case UnaryExpr(_, expr) =>
                et(AssignExpr(expr, "+=", Constant("1")))

            case SizeOfExprT(_) => CInt()
            case SizeOfExprU(_) => CInt()
            case UnaryOpExpr(kind, expr) =>
                val exprType = et(expr).toValue
                kind match {
                    //TODO complete list: __real__ __imag__
                    //TODO promotions
                    case "+" => if (isArithmetic(exprType)) exprType else CUnknown("incorrect type, expected arithmetic, was " + exprType)
                    case "-" => if (isArithmetic(exprType)) exprType else CUnknown("incorrect type, expected arithmetic, was " + exprType)
                    case "~" => if (isIntegral(exprType)) exprType else CUnknown("incorrect type, expected integer, was " + exprType)
                    case "!" => if (isScalar(exprType)) exprType else CUnknown("incorrect type, expected scalar, was " + exprType)
                    case "&&" => CPointer(CVoid()) //label dereference
                    case _ => CUnknown("unknown unary operator " + kind + " (TODO)")
                }
            //x?y:z  (gnuc: x?:z === x?x:z)
            case ConditionalExpr(condition, thenExpr, elseExpr) => getConditionalExprType(__makeOne(ctype(thenExpr.getOrElse(condition))), __makeOne(ctype(elseExpr)))
            //compound statement in expr. ({a;b;c;}), type is the type of the last statement
            case CompoundStatementExpr(compoundStatement) =>
                //TODO variability (there might be alternative last statements)
                __makeOne(getStmtType(compoundStatement))
            case ExprList(exprs) => //comma operator, evaluated left to right, last expr yields value and type; like compound statement expression
                //TODO variability (there might be alternative last statements)
                et(exprs.last.entry)
            case LcurlyInitializer(inits) => CCompound() //TODO more specific checks, currently just use CCompound which can be cast into any structure or array
            case GnuAsmExpr(_, _, _) => CIgnore() //don't care about asm now
            case BuiltinOffsetof(_, _) => CSigned(CInt())
            case c: BuiltinTypesCompatible => CSigned(CInt()) //http://www.delorie.com/gnu/docs/gcc/gcc_81.html
            case c: BuiltinVaArgs => CSigned(CInt())

            //TODO initializers 6.5.2.5
            case e => CUnknown("unknown expression " + e + " (TODO)")
        }
    }

    private def getConditionalExprType(thenType: CType, elseType: CType) = (thenType.toValue, elseType.toValue) match {
        case (CPointer(CVoid()), CPointer(_)) => CPointer(CVoid())
        case (CPointer(_), CPointer(CVoid())) => CPointer(CVoid())
        case (t1, t2) if (coerce(t1, t2)) => wider(t1, t2) //TODO check
        case (t1, t2) => CUnknown("different address spaces in conditional expression: " + t1 + " and " + t2)
    }


    /**
     * defines types of various operations
     * TODO currently incomplete and possibly incorrect
     */
    def operationType(op: String, type1: CType, type2: CType): CType = {
        def pointerArthOp(o: String) = Set("+", "-") contains o
        def pointerArthAssignOp(o: String) = Set("+=", "-=") contains o
        def assignOp(o: String) = Set("+=", "/=", "-=", "*=", "%=", "<<=", ">>=", "&=", "|=") contains o
        def compOp(o: String) = Set("==", "!=", "<", ">", "<=", ">=") contains o
        def artithmeticOp(o: String) = Set("+", "-", "*", "/", "%") contains o
        def logicalOp(o: String) = Set("&&", "||") contains o
        def bitwiseOp(o: String) = Set("&", "|", "^", "<<", ">>", "~") contains o

        (op, type1.toValue, type2.toValue) match {
            case (o, t1, t2) if (pointerArthOp(o) && isArithmetic(t1) && isArithmetic(t2) && coerce(t1, t2)) => t1
            case (o, t1, t2) if (pointerArthOp(o) && isPointer(t1) && isIntegral(t2)) => t1
            case (o, t1, t2) if (pointerArthOp(o) && isPointer(t2) && isIntegral(t1)) => t2
            //bitwise operations defined on isIntegral
            case (op, t1, t2) if (bitwiseOp(op) && isIntegral(t1) && isIntegral(t2)) => wider(t1, t2)

            case ("*", t1, t2) if (isArithmetic(t1) && isArithmetic(t2) && coerce(t1, t2)) => wider(t1, t2)
            case ("/", t1, t2) if (isArithmetic(t1) && isArithmetic(t2) && coerce(t1, t2)) => wider(t1, t2)
            case ("%", t1, t2) if (isIntegral(t1) && isIntegral(t2) && coerce(t1, t2)) => wider(t1, t2)
            case (op, t1, t2) if (compOp(op) && isScalar(t1) && isScalar(t2)) => CSigned(CInt())
            case ("=", _, t2) if (type1.isObject) => t2
            case (o, t1, t2) if (logicalOp(o) && isScalar(t1) && isScalar(t2)) => CSigned(CInt())
            case (o, t1, t2) if (assignOp(o) && type1.isObject && coerce(t1, t2)) => t2
            case (o, t1, t2) if (pointerArthAssignOp(o) && type1.isObject && isPointer(t1) && isIntegral(t2)) => t1
            case _ => CUnknown("unknown operation or incompatible types " + type1 + " " + op + " " + type2)
        }
    }


    /**
     * returns the wider of two types for automatic widening
     * TODO check correctness
     */
    def wider(t1: CType, t2: CType) =
        if (t2 < t1) t2 else t1


    private def createSum(a: Expr, b: Expr) =
        NAryExpr(a, List(Opt(FeatureExpr.base, NArySubExpr("+", b))))


    private def typeFunctionCall(expr: AST, parameterTypes: Seq[CType], retType: CType, _foundTypes: List[CType]): CType = {
        var expectedTypes = parameterTypes
        var foundTypes = _foundTypes
        //variadic macros
        if (expectedTypes.lastOption == Some(CVarArgs())) {
            expectedTypes = expectedTypes.dropRight(1)
            foundTypes = foundTypes.take(expectedTypes.size)
        }
        else if (expectedTypes.lastOption == Some(CVoid()))
            expectedTypes = expectedTypes.dropRight(1)

        //check parameter size and types
        if (expectedTypes.size != foundTypes.size)
            CUnknown("parameter number mismatch in " + expr + " (expected: " + parameterTypes + ")")
        else
        if ((foundTypes zip expectedTypes) forall {
            case (ft, et) => coerce(ft, et)
        }) retType
        else
            CUnknown("parameter type mismatch: expected " + parameterTypes + " found " + foundTypes)
    }


}
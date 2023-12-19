package de.fosd.typechef.crewrite

import de.fosd.typechef.parser.c._
import de.fosd.typechef.featureexpr.{FeatureExprFactory, FeatureExpr}
import de.fosd.typechef.conditional.Opt
import de.fosd.typechef.parser.c.FunctionDef
import java.io.Writer
import java.util

trait CFGWriter {

    def writeNode(node: AST, fexpr: FeatureExpr, containerName: String)

    def writeEdge(source: AST, target: AST, fexpr: FeatureExpr, lookupFExpr: AST => FeatureExpr)

    def writeFooter()

    def writeHeader(filename: String)

    def close()

    protected val printedNodes = new util.IdentityHashMap[AST, Object]()
    protected val FOUND = new Object()

    protected val astToStableIDMap = scala.collection.mutable.Map[(AST, String), String]()
    protected val astToContainerIDMap =  scala.collection.mutable.Map[(AST, String), (String, String)]()
    protected val astChildrenInContainerCounter = scala.collection.mutable.Map[(String, String), Int]()
    protected val astToPositionInContainerMap = scala.collection.mutable.Map[(AST, String), Int]()

    protected def generateStableID(o: AST, fexpr: FeatureExpr): String = o match {
        case FunctionDef(_, decl, _, _) => (decl.getName + fexpr.toTextExpr).hashCode().toString()
        case s: Statement => {
            val fexrpText = fexpr.toTextExpr
            val astKey = (o, fexrpText)
            val container = astToContainerIDMap.get(astKey)

            val positionInContainer = astToPositionInContainerMap.get(astKey)
            val content = escContent(PrettyPrinter.print(s))

            val astIDParameters = content.toString() + container.toString() + positionInContainer + fexrpText 
            val astNodeID = astIDParameters.hashCode().toString()
            astNodeID
        }
        case e: Expr => {
            val fexrpText = fexpr.toTextExpr
            val astKey = (o, fexrpText)
            val container = astToContainerIDMap.get(astKey)

            val positionInContainer = astToPositionInContainerMap.get(astKey)
            val content = escContent(PrettyPrinter.print(e))

            val astIDParameters = content.toString() + container.toString() + positionInContainer + fexrpText 
            val astNodeID = astIDParameters.hashCode().toString()
            astNodeID
        }
        case Declaration(_, initDecl) => {
            val fexrpText = fexpr.toTextExpr
            val astKey = (o, fexrpText)
            val container = astToContainerIDMap.get(astKey)

            val positionInContainer = astToPositionInContainerMap.get(astKey)
            val content = initDecl.map(_.entry.getName).mkString(",")

            val astIDParameters = content.toString() + container.toString() + positionInContainer + fexrpText 
            val astNodeID = astIDParameters.hashCode().toString()
            astNodeID
        }
        case _ => System.identityHashCode(o).toString()
    }

    private def escContent(i: String) = {
        i.replace(";", "").
            replace("\n", " ")
    }

    protected def writeNodeOnce(o: AST, fexpr: () => FeatureExpr, containerName: String) {
        if (!printedNodes.containsKey(o)) {
            printedNodes.put(o, FOUND)
            val featureExpr = fexpr()
            val featureExprText = featureExpr.toTextExpr
            val astKey = (o, featureExprText)

            // Generate the ID for the container which contains the current Node
            val containerID = (containerName, featureExprText)
            astToContainerIDMap.put(astKey, containerID)
            // Now, we must map the currentNode to its position on the tree
            // 1. First, we get the currentPosition in the tree
            val currentAstPositionInContainer = astChildrenInContainerCounter.get(containerID)
                 .getOrElse(0)
            val updatedAstPositionInContainer = currentAstPositionInContainer + 1
            astChildrenInContainerCounter.put(containerID, updatedAstPositionInContainer)
            // Then, we map the current Node to its new position
            astToPositionInContainerMap.put(astKey, updatedAstPositionInContainer)
            val astNodeID = generateStableID(o, featureExpr)

            astToStableIDMap.put(astKey, astNodeID)
            writeNode(o, featureExpr, containerName)
        }
    }

    def writeMethodGraph(m: List[(AST, List[Opt[AST]])], lookupFExpr: AST => FeatureExpr, containerName: String) {
        // iterate ast elements and its successors and add nodes in for each ast element
        for ((o, csuccs) <- m) {
            writeNodeOnce(o, ()=>lookupFExpr(o), containerName)

            // iterate successors and add edges
            for (Opt(f,  succ) <- csuccs) {
                writeNodeOnce(succ, ()=>lookupFExpr(succ), containerName)

                writeEdge(o, succ, f, lookupFExpr)
            }
        }
    }

}

class DotGraph(fwriter: Writer) extends IOUtilities with CFGWriter {

    protected val normalNodeFontName = "Calibri"
    protected val normalNodeFontColor = "black"
    protected val normalNodeFillColor = "white"

    private val externalDefNodeFontColor = "blue"

    private val featureNodeFillColor = "#CD5200"

    protected val normalConnectionEdgeColor = "black"
    // https://mailman.research.att.com/pipermail/graphviz-interest/2001q2/000042.html
    protected val normalConnectionEdgeThickness = "setlinewidth(1)"

    private val featureConnectionEdgeColor = "red"

    private def asText(o: AST): String = o match {
        case FunctionDef(_, decl, _, _) => "Function " + o.getPositionFrom.getLine + ": " + decl.getName
        case s: Statement => "Stmt " + s.getPositionFrom.getLine + ": " + PrettyPrinter.print(s)
        case e: Expr => "Expr " + e.getPositionFrom.getLine + ": " + PrettyPrinter.print(e)
        case Declaration(_, initDecl) => "Decl " + o.getPositionFrom.getLine + ": " + initDecl.map(_.entry.getName).mkString(", ")
        case x => esc(PrettyPrinter.print(x))
    }


    def writeEdge(source: AST, target: AST, fexpr: FeatureExpr, lookupFExpr: AST => FeatureExpr) {
        fwriter.write("\"" + System.identityHashCode(source) + "\" -> \"" + System.identityHashCode(target) + "\"")
        fwriter.write("[")

        fwriter.write("label=\"" + fexpr.toTextExpr + "\", ")
        fwriter.write("color=\"" + (if (fexpr.isTautology()) normalConnectionEdgeColor else featureConnectionEdgeColor) + "\", ")
        fwriter.write("style=\"" + normalConnectionEdgeThickness + "\"")
        fwriter.write("];\n")
    }

    def writeNode(o: AST, fexpr: FeatureExpr, containerName: String) {
        val op = esc(asText(o))
        fwriter.write("\"" + System.identityHashCode(o) + "\"")
        fwriter.write("[")
        fwriter.write("label=\"{{" + op + "}|" + esc(fexpr.toString) + "}\", ")

        fwriter.write("color=\"" + (if (o.isInstanceOf[ExternalDef]) externalDefNodeFontColor else normalNodeFontColor) + "\", ")
        fwriter.write("fontname=\"" + normalNodeFontName + "\", ")
        fwriter.write("style=\"filled\"" + ", ")
        fwriter.write("fillcolor=\"" + (if (fexpr.isTautology()) normalNodeFillColor else featureNodeFillColor) + "\"")

        fwriter.write("];\n")
    }

    def writeFooter() {
        fwriter.write("}\n")
    }

    def writeHeader(title: String) {
        fwriter.write("digraph \"" + title + "\" {" + "\n")
        fwriter.write("node [shape=record];\n")
    }

    protected def esc(i: String) = {
        i.replace("\n", "\\l").
            replace("{", "\\{").
            replace("}", "\\}").
            replace("<", "\\<").
            replace(">", "\\>").
            replace("\"", "\\\"").
            replace("|", "\\|").
            replace(" ", "\\ ").
            replace("\\\"", "\\\\\"").
            replace("\\\\\"", "\\\\\\\"").
            replace("\\\\\\\\\"", "\\\\\\\"")
    }

    def close() {
        fwriter.close()
    }


}


class CFGCSVWriter(fwriter: Writer) extends CFGWriter with IOUtilities {
    /**
     * output format in CSV
     *
     * we distinguish nodes and edges, nodes start with "N" edges with "E"
     *
     * nodes have the following format:
     *
     * N;id;kind;line;name[::container];featureexpr;container
     *
     * * id is an identifier that only has a meaning within a file and that is not stable over multiple runs
     *
     * * kind is one of "function|function-inline|function-static|declaration|statement|expression|unknown"
     *   functions are distinguished into functions with an inline or a static modifier (inline takes precedence)
     *
     * * line refers to the starting position in the .pi file
     *
     * * name is either the name of a function or some debug information together with the name of the containing function.
     *   For functions and declarations, the name is used as a reference and can be used to match nodes across files.
     *   For expressions and statements, the name is used for debugging only and returns the first characters of the statement.
     *   In this case, the name is however followed by :: and the function name that can be used to extract hierarchy information.
     *   Note that function names should be unique in the entire system for each configuration (that is, there may be multiple
     *   functions with the same name but mutually exclusive feature expressions)
     *
     * * featureexpr describes the condition when the node is included
     *
     *
     *
     * edges do not have a line and title:
     *
     * E;sourceid;targetid;featureexpr
     *
     * they connect nodes within a file
     * ids refer to node ids within the file
     * nodeids are always declared before edges connecting them
     *
     * edges between files are not described in the output, but must be computed separately with an external linker
     * that matches nodes based on function/declaration names
     */

    private def asText(o: AST, containerName: String): String = o match {
        case FunctionDef(specs, decl, _, _) =>
            //functions are tagged as inline or static if that modifier occurs at all. not handling conditional
            //modifiers correctly yet
            (if (specs.map(_.entry).contains(InlineSpecifier())) "function-inline;"
            else if (specs.map(_.entry).contains(StaticSpecifier())) "function-static;"
            else "function;") +
                o.getPositionFrom.getLine + ";" + decl.getName
        case s: Statement => "statement;" + s.getPositionFrom.getLine + ";" + esc(PrettyPrinter.print(s))+"::"+containerName
        case e: Expr => "expression;" + e.getPositionFrom.getLine + ";" + esc(PrettyPrinter.print(e))+"::"+containerName
        case Declaration(_, initDecl) => "declaration;" + o.getPositionFrom.getLine + ";" + initDecl.map(_.entry.getName).mkString(",")
        case x => "unknown;" + x.getPositionFrom.getLine + ";" + esc(PrettyPrinter.print(x))+"::"+containerName
    }

    def writeEdge(source: AST, target: AST, fexpr: FeatureExpr, lookupFExpr: AST => FeatureExpr) {
        val featureExprText = fexpr.toTextExpr
        val sourceAstKey = (source, lookupFExpr(source).toTextExpr)
        val targetAstKey = (target, lookupFExpr(target).toTextExpr)

        fwriter.write("E;" + astToStableIDMap.get(sourceAstKey).getOrElse(null) + ";" + astToStableIDMap.get(targetAstKey).getOrElse(null) + ";" + featureExprText + "\n")
    }

    def writeNode(o: AST, fexpr: FeatureExpr, containerName: String) {
        val featureExprText = fexpr.toTextExpr
        val astKey = (o, featureExprText)
        fwriter.write("N;" + astToStableIDMap.get(astKey).getOrElse(null) + ";" + asText(o, containerName) + ";" + featureExprText + "\n")
    }

    def writeFooter() {
    }

    def writeHeader(title: String) {
    }

    private def esc(i: String) = {
        i.replace(";", "").
            replace("\n", " ")
    }

    def close() {
        fwriter.close()
    }
}


class ComposedWriter(writers: List[CFGWriter]) extends CFGWriter {

    def writeNode(node: AST, fexpr: FeatureExpr, containerName: String) {
        writers.map(_.writeNode(node, fexpr, containerName))
    }

    def writeEdge(source: AST, target: AST, fexpr: FeatureExpr, lookupFExpr: AST => FeatureExpr) {
        writers.map(_.writeEdge(source, target, fexpr, lookupFExpr))
    }

    def writeFooter() {
        writers.map(_.writeFooter())
    }

    def writeHeader(filename: String) {
        writers.map(_.writeHeader(filename))
    }

    def close() {
        writers.map(_.close())
    }
}
